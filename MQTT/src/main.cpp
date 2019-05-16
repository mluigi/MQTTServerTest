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

#define MQTT_HOST IPAddress(192, 168, 1, 58)
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
  //uint16_t packetIdSub = mqttClient.subscribe("test/lol", 2);
  //Serial.print("Subscribing at QoS 2, packetId: ");
  //Serial.println(packetIdSub);
  // mqttClient.publish("temperature", 0, true, "55.5");
  // Serial.println("Publishing at QoS 0");
  // uint16_t packetIdPub1 = mqttClient.publish("temperature", 1, true, "55.5");
  // Serial.print("Publishing at QoS 1, packetId: ");
  // Serial.println(packetIdPub1);
  // uint16_t packetIdPub2 = mqttClient.publish("temperature", 2, true, "55.5");
  // Serial.print("Publishing at QoS 2, packetId: ");
  // Serial.println(packetIdPub2);
  // if (WiFi.isConnected())
  // {
  //   xTimerStart(mqttReconnectTimer, 0);
  // }
}

void onMqttDisconnect(AsyncMqttClientDisconnectReason reason)
{
  while (mqttClient.connected())
  {
    Serial.print(".");
    delay(1000);
  }
  Serial.println("Disconnected from MQTT.");
  
  Serial.println("Going to sleep now");
  Serial.flush();
  esp_deep_sleep_start();
  Serial.println("This will never be printed");
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
  Serial.print("  topic: ");
  Serial.println(topic);
  Serial.print("  qos: ");
  Serial.println(properties.qos);
  Serial.print("  dup: ");
  Serial.println(properties.dup);
  Serial.print("  retain: ");
  Serial.println(properties.retain);
  Serial.print("  len: ");
  Serial.println(len);
  Serial.print("  index: ");
  Serial.println(index);
  Serial.print("  total: ");
  Serial.println(total);
}

void onMqttPublish(uint16_t packetId)
{
  Serial.println("Publish acknowledged.");
  Serial.print("  packetId: ");
  Serial.println(packetId);
}

void disconnect(){
  mqttClient.disconnect();
}

// RTC_DATA_ATTR int bootCount = 0;

void setup()
{
  Serial.begin(115200);
  Serial.println();
  Serial.println();
  delay(1000);
  // ++bootCount;
  // Serial.println("Boot number: " + String(bootCount));
  // print_wakeup_reason();
  // esp_sleep_enable_timer_wakeup(TIME_TO_SLEEP * uS_TO_S_FACTOR);
  // Serial.println("Setup ESP32 to sleep for every " + String(TIME_TO_SLEEP) + " Seconds");
  mqttReconnectTimer = xTimerCreate("mqttTimer", pdMS_TO_TICKS(1000), pdFALSE, (void *)0, reinterpret_cast<TimerCallbackFunction_t>(disconnect));
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
}

void loop()
{

  //Calcolo lato server media latenza tra messaggi (-10 ms se si usa il delay)
  for (int i = 0; i < 500; ++i)
  {
    char buf[50];
    mqttClient.publish("temperature",0,false,ltoa(millis(),buf,10));
    delay(10);
  }
  
  //Calcolo latenza risposta dal server per publish ripetuti
  
}