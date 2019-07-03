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

$sql = "UPDATE Sessions SET QOS0Packets=0, QOS1Packets=0, packetsSent=0";
$result=$conn->query($sql);
$conn->close();

