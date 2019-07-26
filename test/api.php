<?php

$host = "192.168.43.116";
$user = "test";
$pass = "";

$databaseName = "test";
$tableName = "Times";


$conn = new mysqli($host, $user, $pass, $databaseName);

if ($conn->connect_error) {
    die("Connection failed: " . $conn->connect_error);
}
$sql = "SELECT * FROM (SELECT * FROM $tableName ORDER BY id DESC LIMIT 1000) sub ORDER BY id ASC";
$result = $conn->query($sql);
$array = $result->fetch_all(MYSQLI_ASSOC);
$timesarray = array();
$idarray = array();
$devIdarray = array();
foreach ($array as $item) {
    array_push($timesarray, $item["time"]);
    array_push($idarray, $item["id"]);
    array_push($devIdarray, $item["deviceId"]);
}
$result->free();

$sql = "SELECT sum(QOS0PacketsReceived) as QOS0PacketsReceived, sum(QOS1PacketsReceived) as QOS1PacketsReceived, sum(pubackSent) as pubackSent,
        sum(QOS0PacketsSent) as QOS0PacketsSent, sum(QOS1PacketsSent) as QOS1PacketsSent, sum(pubackReceived) as pubackReceived
        FROM Devices ORDER BY id DESC LIMIT 1";
$result=$conn->query($sql);
$sessionData = $result->fetch_all(MYSQLI_ASSOC);

$result->free();

$sql = "SELECT id,sum(QOS0PacketsSent) as totQOS0Sent, sum(QOS1PacketsSent) as totQOS1Sent, sum(pubackReceived) as 
    totPubACKReceived from test.Devices group by Devices.ip order by id desc LIMIT 2";
$result=$conn->query($sql);
$devicesData = $result->fetch_all(MYSQLI_ASSOC);

$conn->close();

echo json_encode(array(
    $sessionData[0],
    $timesarray,
    $idarray,
    $devIdarray,
    $devicesData
));
