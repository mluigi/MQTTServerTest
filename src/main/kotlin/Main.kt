import Times.time
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Vertx
import io.vertx.mqtt.MqttServer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.concurrent.scheduleAtFixedRate

val dbLog: Logger = LoggerFactory.getLogger("DB")
val mqttLog: Logger = LoggerFactory.getLogger("MQTT")

val db = Database.connect(
    "jdbc:mariadb://localhost:3306/test", driver = "org.mariadb.jdbc.Driver",
    user = "test", password = ""
)

val mqttServer: MqttServer = MqttServer.create(Vertx.vertx())   //creazione server MQTT

object Devices : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", 50).uniqueIndex()
}

object Times : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val sessionId = integer("sessionId") references Sessions.id
    val time = long("time")
}

object Sessions : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val startDate = datetime("startDate")
    val QOS0Packets = integer("QOS0Packets")
    val QOS1Packets = integer("QOS1Packets")
    val packetsSent = integer("packetsSent")
}


fun main() {
    var sesId = 0
    transaction(db) {       //creazione tabelle database
        SchemaUtils.drop(Devices, Sessions, Times)      //svuotamento database
        SchemaUtils.create(Devices, Sessions, Times)        //creazione database
        sesId = Sessions.insert {       //inizializzazione database
            it[startDate] = DateTime.now()
            it[QOS0Packets] = 0
            it[QOS1Packets] = 0
            it[packetsSent] = 0
        } get Sessions.id
        dbLog.info("Created Devices, Sessions and Times tables.")
    }

    val lock = Any()        //serve per il semaforo

    val packetsAMO = ArrayList<Pair<Int, Long>>()
    val packetsALO = ArrayList<Pair<Int, Long>>()

    var pubackSent = 0

    //gestore connessioni al server, cattura dell'evento di connessione
    mqttServer.endpointHandler { endpoint ->
        mqttLog.info("MQTT client [${endpoint.clientIdentifier()}] request to connect, clean session = ${endpoint.isCleanSession}")
        var devId: Int
        transaction(db) {
            devId = Devices.insertIgnore {
                it[name] = endpoint.clientIdentifier()
            } get Devices.id
        }
        if (endpoint.auth() != null) {
            mqttLog.info("[username = ${endpoint.auth().username}, password = ${endpoint.auth().password}]")
        }

        mqttLog.info("[keep alive timeout = ${endpoint.keepAliveTimeSeconds()}]")

        var prev = 0L

        //gestore evento di publish
        endpoint.publishHandler {
            when (it.qosLevel()) {
                MqttQoS.AT_MOST_ONCE -> {
                    val curr = System.nanoTime()    //salvo l'istante di tempo in cui ho ricevuto il pacchetto
                    synchronized(lock) {
                        if (prev != 0L) {
                            packetsAMO.add(Pair(it.messageId(), curr - prev))       //aggiungo [idMessaggio, differenza tra t(mess corrente) e t(mess precedente)] all'array
                        }
                    }
                    prev = curr
                }
                MqttQoS.AT_LEAST_ONCE -> {
                    endpoint.publishAcknowledge(it.messageId())
                    ++pubackSent
                    val curr = System.nanoTime()       //salvo l'istante di tempo in cui ho ricevuto il pacchetto
                    synchronized(lock) {
                        if (prev != 0L) {
                            packetsALO.add(Pair(it.messageId(), curr - prev))       //aggiungo [idMessaggio, differenza tra t(mess corrente) e t(mess precedente)] all'array
                        }
                    }
                    prev = curr
                }
                MqttQoS.EXACTLY_ONCE -> endpoint.publishReceived(it.messageId())
                else -> {

                }
            }
        }.publishReleaseHandler {
            endpoint.publishComplete(it)
        }.publishAcknowledgeHandler {
        }.disconnectHandler {
            mqttLog.info("MQTT client [${endpoint.clientIdentifier()}] disconnected.")
        }.exceptionHandler {
            mqttLog.info("${it.cause}: ${it.message}")
        }.accept(true)      //accetta connessione
    }.exceptionHandler {
        mqttLog.info("${it.cause}: ${it.message}")
    }.listen { ar ->        //mette in ascolto il server
        if (ar.succeeded()) {
            mqttLog.info("MQTT server is listening on port ${ar.result().actualPort()}")
        } else {
            mqttLog.error("Error on starting the server")
            ar.cause().printStackTrace()
        }
    }
    Timer().scheduleAtFixedRate(0, 1000) {
        synchronized(lock) {
            if (packetsALO.size > 0 || packetsAMO.size > 0 || pubackSent > 0) {     //controllo se ho ricevuto messaggi
                mqttLog.info("Received ${packetsAMO.size + packetsALO.size} packets")
                mqttLog.info("${packetsAMO.size} QoS 1, ${packetsALO.size} QoS 2, PUBACKs sent $pubackSent")
                val bothTimes = packetsALO.plus(packetsAMO).map { it.second }.toLongArray()
                mqttLog.info(
                    "Time between packages: min ${bothTimes.min()!!}ns " +
                            "max ${"%.2f".format(bothTimes.max()!!.toDouble() / 1_000_000)}ms " +
                            "avg ${"%.2f".format((bothTimes.average() / 1_000_000))}ms"
                )

                transaction(db) {       
                    Times.batchInsert(packetsALO    //inserisco l'array creato sotto nel database
                        .plus(packetsAMO)       //unione packetsAMO & packetsALO
                        .sortedBy { it.first }  //ordino per idMessaggio
                        .map { it.second }) { timeBetweenPackets ->     //creo un array di [differenza tra t(mess corrente) e t(mess precedente)]
                        this[time] = timeBetweenPackets
                        this[Times.sessionId] = sesId
                    }
                    Sessions.update(where = { Sessions.id eq sesId }) {     //aggiorno la riga della sessione
                        with(SqlExpressionBuilder) {
                            it.update(QOS0Packets, QOS0Packets + packetsAMO.size)
                            it.update(QOS1Packets, QOS1Packets + packetsALO.size)
                            it.update(packetsSent, packetsSent + pubackSent)
                        }
                    }
                }

                packetsALO.clear()      //azzero i dati temporanei
                packetsAMO.clear()
                pubackSent = 0
            }
        }
    }
}


