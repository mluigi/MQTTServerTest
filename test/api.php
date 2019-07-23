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

$sql = "SELECT * FROM Sessions ORDER BY id DESC LIMIT 1";
$result=$conn->query($sql);
$data = $result->fetch_all(MYSQLI_ASSOC);

$conn->close();

echo json_encode(array(
    $data[0],
    $timesarray,
    $idarray,
    $devIdarray
));
