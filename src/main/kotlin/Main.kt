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

object Devices : Table() {      //creazione tabella dei dispositivi
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", 50).uniqueIndex()
}

object Times : Table() {    //creo tabella dei tempi
    val id = integer("id").autoIncrement().primaryKey()
    val sessionId = integer("sessionId") references Sessions.id
    val time = long("time")
    val deviceId = integer("deviceId") references Devices.id
}

object Sessions : Table() {     //creo tabella delle sessioni
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
        dbLog.info("Create tabelle Devices, Sessions e Times.")
    }

    val lock = Any()        //serve per il semaforo

    val packetsAMO = ArrayList<Pair<Int, Long>>()
    val packetsALO = ArrayList<Pair<Int, Long>>()
    val mesToDevIdMap = HashMap<Int, Int>()
    var pubackSent = 0
    var richieste = 0

    //gestore connessioni al server, cattura dell'evento di connessione
    mqttServer.endpointHandler { endpoint ->
        mqttLog.info("MQTT client [${endpoint.clientIdentifier()}] richiesta di connessione, clean session = ${endpoint.isCleanSession}")
        richieste++
        if (richieste > 4) {
            mqttServer.close()
            mqttLog.info("Riavviando")
            mqttServer.listen()

            richieste = 0
        }
        var devId = 0
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
                        if (prev != 0L && curr - prev < 700_000_000) {
                            packetsAMO.add(
                                Pair(
                                    it.messageId(),
                                    curr - prev
                                )
                            ) //aggiungo [idMessaggio, differenza tra t(mess corrente) e t(mess precedente)] all'array
                            mesToDevIdMap[it.messageId()] = devId
                        }
                    }
                    prev = curr
                }
                MqttQoS.AT_LEAST_ONCE -> {
                    endpoint.publishAcknowledge(it.messageId())
                    ++pubackSent
                    val curr = System.nanoTime()       //salvo l'istante di tempo in cui ho ricevuto il pacchetto
                    synchronized(lock) {
                        if (prev != 0L && curr - prev < 700_000_000) {
                            packetsALO.add(Pair(it.messageId(), curr - prev))       //aggiungo [idMessaggio, differenza tra t(mess corrente) e t(mess precedente)] all'array
                            mesToDevIdMap[it.messageId()] = devId
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
            mqttLog.info("MQTT client [${endpoint.clientIdentifier()}] disconnesso.")
        }.exceptionHandler {
            mqttLog.info("${it.cause}: ${it.message}")
        }.accept(true)      //accetta connessione
    }.exceptionHandler {
        mqttLog.info("${it.cause}: ${it.message}")
    }.listen { ar ->        //mette in ascolto il server
        if (ar.succeeded()) {
            mqttLog.info("Il server MQTT è in ascolto sul porto ${ar.result().actualPort()}.")
        } else {
            mqttLog.error("Errore sull'avvio del server.")
            ar.cause().printStackTrace()
        }
    }
    Timer().scheduleAtFixedRate(0, 1000) {
        synchronized(lock) {
            if (packetsALO.size > 0 || packetsAMO.size > 0 || pubackSent > 0) {     //controllo se ho ricevuto messaggi
                mqttLog.info("Ricevuti ${packetsAMO.size + packetsALO.size} pacchetti")
                mqttLog.info("${packetsAMO.size} QoS 1, ${packetsALO.size} QoS 2, PUBACKs inviato $pubackSent")
                val bothTimes = packetsALO.plus(packetsAMO).sortedBy { it.first }.map { it.second }
                mqttLog.info(
                    "Tempo tra i pacchetti: min ${bothTimes.toLongArray().min()!!}ns " +
                            "max ${"%.2f".format(bothTimes.toLongArray().max()!!.toDouble() / 1_000_000)}ms " +
                            "avg ${"%.2f".format((bothTimes.toLongArray().average() / 1_000_000))}ms"
                )

                transaction(db) {
                    Times.batchInsert(packetsALO.plus(packetsAMO).sortedBy { it.first }) { (messageid, times) ->
                        //creo un array di [differenza tra t(mess corrente) e t(mess precedente)]
                        val devId = mesToDevIdMap[messageid]
                        this[time] = times
                        this[Times.sessionId] = sesId
                        this[Times.deviceId] = devId!!
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


