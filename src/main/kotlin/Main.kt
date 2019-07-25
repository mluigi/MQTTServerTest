import Times.time
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Vertx
import io.vertx.mqtt.MqttServer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
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
    val ip = varchar("ip", 30)
    val QOS0PacketsSent = integer("QOS0PacketsSent").nullable()
    val QOS1PacketsSent = integer("QOS1PacketsSent").nullable()
    val pubackReceived = integer("pubackReceived").nullable()
    val QOS0PacketsReceived = integer("QOS0PacketsReceived")
    val QOS1PacketsReceived = integer("QOS1PacketsReceived")
    val pubackSent = integer("pubackSent")
}

object Times : Table() {    //creo tabella dei tempi
    val id = integer("id").autoIncrement().primaryKey()
    val time = long("time")
    val deviceId = integer("deviceId") references Devices.id
}

fun main() {

    transaction(db) {
        //creazione tabelle database
        SchemaUtils.drop(Devices, Times)      //svuotamento database
        SchemaUtils.create(Devices, Times)        //creazione database
        dbLog.info("Create tabelle Devices e Times.")
    }

    val lock = Any()        //serve per il semaforo

    val mesIdToDevIdMap = HashMap<Int, Int>()
    val devIdToTimesAMO = HashMap<Int, ArrayList<Pair<Int, Long>>>()
    val devIdToTimesALO = HashMap<Int, ArrayList<Pair<Int, Long>>>()
    val devIdToPuback = HashMap<Int, Int>()
    //Dati ricevuti dai dispositivi
    val devIdToDataReceived = HashMap<Int, Triple<Int, Int, Int>>()

    //gestore connessioni al server, cattura dell'evento di connessione
    mqttServer.endpointHandler { endpoint ->
        mqttLog.info("MQTT client [${endpoint.clientIdentifier()}] richiesta di connessione, clean session = ${endpoint.isCleanSession}")

        var devId = 0
        transaction(db) {
            devId = Devices.insert {
                it[name] = endpoint.clientIdentifier()
                it[ip] = endpoint.remoteAddress().host()
                it[QOS0PacketsReceived] = 0
                it[QOS1PacketsReceived] = 0
                it[pubackSent] = 0
            } get Devices.id
        }
        devIdToTimesAMO[devId] = ArrayList()
        devIdToTimesALO[devId] = ArrayList()
        devIdToPuback[devId] = 0
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
                        //if (prev != 0L && curr - prev < 700_000_000) {
                        devIdToTimesAMO[devId]!!.add(
                                Pair(
                                    it.messageId(),
                                    curr - prev
                                )
                            ) //aggiungo [idMessaggio, differenza tra t(mess corrente) e t(mess precedente)] all'array
                        mesIdToDevIdMap[it.messageId()] = devId
                        //}
                    }
                    prev = curr
                }
                MqttQoS.AT_LEAST_ONCE -> {
                    val curr = System.nanoTime()       //salvo l'istante di tempo in cui ho ricevuto il pacchetto
                    synchronized(lock) {
                        endpoint.publishAcknowledge(it.messageId())
                        //if (prev != 0L && curr - prev < 700_000_000) {
                        devIdToPuback[devId] = devIdToPuback[devId]!! + 1
                        devIdToTimesALO[devId]!!.add(
                                Pair(
                                    it.messageId(),
                                    curr - prev
                                )
                        ) //aggiungo [idMessaggio, differenza tra t(mess corrente) e t(mess precedente)] all'array
                        mesIdToDevIdMap[it.messageId()] = devId
                        //}
                    }
                    prev = curr
                }
                MqttQoS.EXACTLY_ONCE -> endpoint.publishReceived(it.messageId())
                else -> {

                }
            }
            try {
                synchronized(lock) {
                    val data = it.payload().toString().split(",").map { it.toInt() }
                    devIdToDataReceived[devId] = Triple(data[0], data[1], data[2])
                }
            } catch (e: Exception) {
                //mqttLog.error(e.message)
            }

        }.publishReleaseHandler {
            endpoint.publishComplete(it)
        }.disconnectHandler {
            mqttLog.info("MQTT client [${endpoint.clientIdentifier()}] disconnesso.")
        }.exceptionHandler {
            mqttLog.info("${it.cause}: ${it.message}")
        }.accept(true)      //accetta connessione
    }.exceptionHandler {
        mqttLog.info("${it.cause}: ${it.message}")
    }.listen { ar ->
        //mette in ascolto il server
        if (ar.succeeded()) {
            mqttLog.info("Il server MQTT è in ascolto sul porto ${ar.result().actualPort()}.")
        } else {
            mqttLog.error("Errore nell'avvio del server.")
            ar.cause().printStackTrace()
        }
    }
    Timer().scheduleAtFixedRate(0, 1000) {
        synchronized(lock) {
            var amoSize = 0
            devIdToTimesAMO.keys.forEach {
                amoSize += devIdToTimesAMO[it]!!.size
            }
            var aloSize = 0
            devIdToTimesALO.keys.forEach {
                aloSize += devIdToTimesALO[it]!!.size
            }
            var pubacks = 0
            devIdToPuback.keys.forEach {
                pubacks += devIdToPuback[it]!!
            }

            if (amoSize > 0 || aloSize > 0 || pubacks > 0) {     //controllo se ho ricevuto messaggi
                val bothTimes = ArrayList<Pair<Int, Long>>().also {
                    devIdToTimesALO.values.forEach { s -> it.addAll(s) }
                    devIdToTimesAMO.values.forEach { s -> it.addAll(s) }
                }.sortedBy { it.first }
                val longArray = bothTimes.map { it.second }.toLongArray()
                mqttLog.info("Ricevuti ${aloSize + amoSize} pacchetti")
                mqttLog.info("$amoSize QoS 0, $aloSize QoS 1, PUBACKs inviato $pubacks")
                mqttLog.info(
                    "Tempo tra i pacchetti: min ${longArray.min()!!}ns " +
                            "max ${"%.2f".format(longArray.max()!!.toDouble() / 1_000_000)}ms " +
                            "avg ${"%.2f".format((longArray.average() / 1_000_000))}ms"
                )

                transaction(db) {
                    Times.batchInsert(bothTimes) { (messageid, times) ->
                        //creo un array di [differenza tra t(mess corrente) e t(mess precedente)]
                        val devId = mesIdToDevIdMap[messageid]
                        this[time] = times
                        this[Times.deviceId] = devId!!
                    }
                    devIdToDataReceived.keys.forEach { devId ->
                        Devices.update(where = { Devices.id eq devId }) {
                            val (qos0, qos1, puback) = devIdToDataReceived[devId]!!
                            it[QOS0PacketsSent] = qos0
                            it[QOS1PacketsSent] = qos1
                            it[pubackReceived] = puback
                        }
                    }

                    devIdToTimesAMO.keys.forEach { devId ->
                        Devices.update(where = { Devices.id eq devId }) {
                            with(SqlExpressionBuilder) {
                                it.update(QOS0PacketsReceived, QOS0PacketsReceived + devIdToTimesAMO[devId]!!.size)
                            }
                        }
                    }
                    devIdToTimesALO.keys.forEach { devId ->
                        Devices.update(where = { Devices.id eq devId }) {
                            with(SqlExpressionBuilder) {
                                it.update(QOS1PacketsReceived, QOS1PacketsReceived + devIdToTimesALO[devId]!!.size)
                            }
                        }
                    }
                    devIdToPuback.keys.forEach { devId ->
                        Devices.update(where = { Devices.id eq devId }) {
                            with(SqlExpressionBuilder) {
                                it.update(pubackSent, pubackSent + devIdToPuback[devId]!!)
                            }
                        }
                    }
                }
                //azzero i dati temporanei
                devIdToPuback.keys.forEach {
                    devIdToPuback[it] = 0
                }
                devIdToTimesALO.keys.forEach {
                    devIdToTimesALO[it]!!.clear()
                }
                devIdToTimesAMO.keys.forEach {
                    devIdToTimesAMO[it]!!.clear()
                }
            }
        }
    }
}


