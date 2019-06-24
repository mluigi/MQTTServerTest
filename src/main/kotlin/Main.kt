import Times.sessionId
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

val mqttServer: MqttServer = MqttServer.create(Vertx.vertx())

object Devices : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", 50).uniqueIndex()
}

object TemperatureReads : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val value = double("value")
    val deviceId = (integer("deviceId") references Devices.id).nullable()
}

object Sessions : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val startDate = date("startDate")
    val deviceId = (integer("deviceId") references Devices.id)
    val nPacketsReceived = integer("nPacketsReceived")
    val nPacketsSent = integer("nPacketsSent")
}

object Times : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val sessionId = integer("sessionId") references Sessions.id
    val time = long("time")
}


fun main() {
    transaction(db) {
        SchemaUtils.drop(Devices, Sessions, Times)
        SchemaUtils.create(Devices, Sessions, Times)
        dbLog.info("Created Devices, Sessions and Times tables.")
    }

//    val tempCache = ArrayList<Pair<Int, String>>()
//
//
//    Timer().scheduleAtFixedRate(2000, 1000) {
//        synchronized(tempCache) {
//            if (tempCache.size > 0) {
//                transaction(db) {
//                    TemperatureReads.batchInsert(tempCache) { (devId, message) ->
//                        this[value] = message.toDouble()
//                        this[deviceId] = devId
//                    }
//
//                    dbLog.info("Added ${tempCache.size} reads")
//                    tempCache.clear()
//                }
//            }
//        }
//    }

    val lock = Any()

    val packetsAMO = ArrayList<Pair<Int, Long>>()
    val packetsALO = ArrayList<Pair<Int, Long>>()

    var pubackSent = 0

    mqttServer.endpointHandler { endpoint ->
        mqttLog.info("MQTT client [${endpoint.clientIdentifier()}] request to connect, clean session = ${endpoint.isCleanSession}")
        var devId = 0
        var sesId = 0
        transaction(db) {
            devId = Devices.insertIgnore {
                it[name] = endpoint.clientIdentifier()
            } get Devices.id
            sesId = Sessions.insert {
                it[deviceId] = devId
                it[startDate] = DateTime.now()
                it[nPacketsReceived] = 0
                it[nPacketsSent] = 0
            } get Sessions.id
        }
        if (endpoint.auth() != null) {
            mqttLog.info("[username = ${endpoint.auth().username}, password = ${endpoint.auth().password}]")
        }

        mqttLog.info("[keep alive timeout = ${endpoint.keepAliveTimeSeconds()}]")

        var prev = 0L

        Timer().scheduleAtFixedRate(0, 1000) {
            synchronized(lock) {
                if (packetsALO.size > 0 || packetsAMO.size > 0 || pubackSent > 0) {
                    mqttLog.info("Received ${packetsAMO.size + packetsALO.size} packets")
                    mqttLog.info("${packetsAMO.size} QoS 1, ${packetsALO.size} QoS 2, PUBACKs sent $pubackSent")
                    val bothTimes = packetsALO.plus(packetsAMO).map { it.second }.toLongArray()
                    mqttLog.info(
                        "Time between packages: min ${bothTimes.min()!!}ns " +
                                "max ${bothTimes.max()!! / 1_000_000}ms " +
                                "avg ${(".2f").format((bothTimes.average() / 1_000_000))}ms"
                    )

                    transaction(db) {
                        Times.batchInsert(packetsALO
                            .plus(packetsAMO)
                            .sortedBy { it.first }
                            .map { it.second }) { timeBetweenPackets ->
                            this[sessionId] = sesId
                            this[time] = timeBetweenPackets
                        }

                        Sessions.update(where = { Sessions.id eq sesId }) {
                            with(SqlExpressionBuilder) {
                                it.update(nPacketsReceived, nPacketsReceived + packetsALO.size + packetsAMO.size)
                                it.update(nPacketsSent, nPacketsSent + pubackSent)
                            }
                        }
                    }

                    packetsALO.clear()
                    packetsAMO.clear()
                    pubackSent = 0
                }
            }
        }


        endpoint.publishHandler {
            when (it.qosLevel()) {
                MqttQoS.AT_MOST_ONCE -> {
                    val curr = System.nanoTime()
                    synchronized(lock) {
                        if (prev != 0L) {
                            packetsAMO.add(Pair(it.messageId(), curr - prev))
                        }
                    }
                    prev = curr
                }
                MqttQoS.AT_LEAST_ONCE -> {
                    endpoint.publishAcknowledge(it.messageId())
                    ++pubackSent
                    val curr = System.nanoTime()
                    synchronized(lock) {
                        if (prev != 0L) {
                            packetsALO.add(Pair(it.messageId(), curr - prev))
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
        }.accept(true)
    }.exceptionHandler {
        mqttLog.info("${it.cause}: ${it.message}")
    }.listen { ar ->
        if (ar.succeeded()) {
            mqttLog.info("MQTT server is listening on port ${ar.result().actualPort()}")
        } else {
            mqttLog.error("Error on starting the server")
            ar.cause().printStackTrace()
        }
    }
}
