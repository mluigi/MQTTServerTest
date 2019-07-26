<?php

$host = "192.168.43.116";
$user = "test";
$pass = "";

$databaseName = "test";

$conn = new mysqli($host, $user, $pass, $databaseName);

if ($conn->connect_error) {
    die("Connection failed: " . $conn->connect_error);
}

$sql = "UPDATE Devices SET QOS0PacketsSent=0, QOS1PacketsSent=0, pubackSent=0, QOS0PacketsReceived=0, QOS1PacketsReceived=0, pubackReceived=0";
$result=$conn->query($sql);
$conn->close();

