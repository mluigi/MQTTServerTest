#include <Arduino.h>
#include <AsyncMqttClient.h>
#include <WiFi.h>
extern "C"
{
#include "freertos/FreeRTOS.h"
#include "freertos/timers.h"
}

#define WIFI_SSID "FASTWEB-tsT92f"
#define WIFI_PASSWORD "meloncello10"

#define MQTT_HOST IPAddress(192, 168, 1, 51)
#define MQTT_PORT 1883

// #define uS_TO_S_FACTOR 1000000 /* Conversion factor for micro seconds to seconds */
// #define TIME_TO_SLEEP 5

AsyncMqttClient mqttClient;
TimerHandle_t mqttReconnectTimer;
TimerHandle_t wifiReconnectTimer;

// void print_wakeup_reason()
// {
//   esp_sleep_wakeup_cause_t wakeup_reason;

//   wakeup_reason = esp_sleep_get_wakeup_cause();

//   switch (wakeup_reason)
//   {
//   case ESP_SLEEP_WAKEUP_EXT0:
//     Serial.println("Wakeup caused by external signal using RTC_IO");
//     break;
//   case ESP_SLEEP_WAKEUP_EXT1:
//     Serial.println("Wakeup caused by external signal using RTC_CNTL");
//     break;
//   case ESP_SLEEP_WAKEUP_TIMER:
//     Serial.println("Wakeup caused by timer");
//     break;
//   case ESP_SLEEP_WAKEUP_TOUCHPAD:
//     Serial.println("Wakeup caused by touchpad");
//     break;
//   case ESP_SLEEP_WAKEUP_ULP:
//     Serial.println("Wakeup caused by ULP program");
//     break;
//   default:
//     Serial.printf("Wakeup was not caused by deep sleep: %d\n", wakeup_reason);
//     break;
//   }
// }

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
    //xTimerStop(mqttReconnectTimer, 0); // ensure we don't reconnect to MQTT while reconnecting to Wi-Fi
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
int i = 1;
long sendTime;
long returnTime;
void onMqttPublish(uint16_t packetId)
{
  returnTime = millis();
  //Serial.printf("%d. Return time = %ld ms\n", i, (returnTime - sendTime));
  ++i;
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

    Serial.println("Sending packets with qos 0");
    for (int i = 0; i < 500; ++i)
    {
      mqttClient.publish("test", 0, false, "pack");
    }
    mqttClient.publish("test", 0, false, "end");
    long end = millis();
    Serial.printf("Took %ld ms\n", (end - start));
    delay(1000);
    start = millis();
    Serial.println("Sending packets with qos 1");
    for (int i = 0; i < 500; ++i)
    {
      mqttClient.publish("test", 1, false, "pack");
    }
    end = millis();
    Serial.printf("Took %ld s\n", (end - start) / 1000);
    //mqttClient.disconnect();
    delay(1000);
    i = 1;
  }
}