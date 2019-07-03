#include <Arduino.h>
#include <AsyncMqttClient.h>
#include <WiFi.h>
extern "C"
{
#include "freertos/FreeRTOS.h"
#include "freertos/timers.h"
}

#define WIFI_SSID "OnePlus6"
#define WIFI_PASSWORD "test1234"

#define MQTT_HOST IPAddress(192,168,43,116)
#define MQTT_PORT 1883

AsyncMqttClient mqttClient;
TimerHandle_t mqttReconnectTimer;
TimerHandle_t wifiReconnectTimer;

void connectToWifi()
{
  Serial.println("Connecting to Wi-Fi...");
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

void connectToMqtt()
{
  Serial.println("Connecting to MQTT...");
  mqttClient.setKeepAlive(4);
  mqttClient.setCleanSession(true);
  mqttClient.connect();
}

void WiFiEvent(WiFiEvent_t event)
{
  Serial.printf("[WiFi-event] event: %d\n", event);
  switch (event)
  {
  case SYSTEM_EVENT_STA_GOT_IP:
    Serial.println("WiFi connected");
    Serial.println("IP address: ");
    Serial.println(WiFi.localIP());
    connectToMqtt();
    break;
  case SYSTEM_EVENT_STA_DISCONNECTED:
    Serial.println("WiFi lost connection");
    xTimerStop(mqttReconnectTimer, 0); 
    xTimerStart(wifiReconnectTimer, 0);
    break;
  }
}

void onMqttConnect(bool sessionPresent)
{
  Serial.println("Connected to MQTT.");
  Serial.print("Session present: ");
  Serial.println(sessionPresent);
}

void onMqttDisconnect(AsyncMqttClientDisconnectReason reason)
{
  while (mqttClient.connected())
  {
    Serial.print(".");
    delay(1000);
  }
  Serial.println("Disconnected from MQTT.");
  if (WiFi.isConnected())
  {
    xTimerStart(mqttReconnectTimer, 0);
  }
}

void onMqttSubscribe(uint16_t packetId, uint8_t qos)
{
  Serial.println("Subscribe acknowledged.");
  Serial.print("  packetId: ");
  Serial.println(packetId);
  Serial.print("  qos: ");
  Serial.println(qos);
}

void onMqttUnsubscribe(uint16_t packetId)
{
  Serial.println("Unsubscribe acknowledged.");
  Serial.print("  packetId: ");
  Serial.println(packetId);
}

void onMqttMessage(char *topic, char *payload, AsyncMqttClientMessageProperties properties, size_t len, size_t index, size_t total)
{
  Serial.println("Publish received.");
}
int pubacks = 0;
long sendTime;
long returnTime;
void onMqttPublish(uint16_t packetId)
{
  returnTime = millis();
  ++pubacks;
}

void setup()
{
  Serial.begin(115200);
  Serial.println();
  Serial.println();
  delay(1000);
  mqttReconnectTimer = xTimerCreate("mqttTimer", pdMS_TO_TICKS(1000), pdFALSE, (void *)0, reinterpret_cast<TimerCallbackFunction_t>(connectToMqtt));
  wifiReconnectTimer = xTimerCreate("wifiTimer", pdMS_TO_TICKS(2000), pdFALSE, (void *)0, reinterpret_cast<TimerCallbackFunction_t>(connectToWifi));

  WiFi.onEvent(WiFiEvent);

  mqttClient.onConnect(onMqttConnect);
  mqttClient.onDisconnect(onMqttDisconnect);
  mqttClient.onSubscribe(onMqttSubscribe);
  mqttClient.onUnsubscribe(onMqttUnsubscribe);
  mqttClient.onMessage(onMqttMessage);
  mqttClient.onPublish(onMqttPublish);
  mqttClient.setServer(MQTT_HOST, MQTT_PORT);

  connectToWifi();
  mqttClient.subscribe("return", 1);
}

void loop()
{
  if (mqttClient.connected())
  {
    long start = millis();

    Serial.println("Sending 500 packets with qos 0");
    for (int i = 0; i < 500; ++i)
    {
      mqttClient.publish("test", 0, false, "pack");
    }
    mqttClient.publish("test", 0, false, "end");
    long end = millis();
    Serial.printf("Took %ld ms\n", (end - start));
    delay(1000);
    start = millis();
    Serial.println("Sending 500 packets with qos 1");
    for (int i = 0; i < 500; ++i)
    {
      mqttClient.publish("test", 1, false, "pack");
    }
    end = millis();
    Serial.printf("Took %ld ms\n", (end - start));
    delay(1000);
    Serial.printf("Received %i PUBACKs after 1 second.\n", pubacks);
    pubacks = 0;
  }
}