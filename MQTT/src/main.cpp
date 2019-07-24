#include <Arduino.h>
#include <AsyncMqttClient.h>
#include <WiFi.h>
#include <time.h>
#include <stdlib.h>
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

void connectToWifi()    //connessione esp al wi-fi
{
  Serial.println("Connettendo al Wi-Fi...");
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

void connectToMqtt()    //funzione di connessione al server
{
  Serial.println("Connettendo al server MQTT...");
  mqttClient.setKeepAlive(4);
  mqttClient.setCleanSession(true);
  mqttClient.connect();
}

void WiFiEvent(WiFiEvent_t event)   //funzione di gestione eventi wi-fi
{
  Serial.printf("[WiFi-event] evento: %d\n", event);
  switch (event)
  {
  case SYSTEM_EVENT_STA_GOT_IP:   //indirizzo IP ottenuto
    Serial.println("WiFi connesso");
    Serial.println("Indirizzo IP: ");
    Serial.println(WiFi.localIP());
    connectToMqtt();
    break;
  case SYSTEM_EVENT_STA_DISCONNECTED:   // connessione wi-fi persa
    Serial.println("WiFi: connessione persa.");
    xTimerStop(mqttReconnectTimer, 0);  //tentativi di riconnessione con timer
    xTimerStart(wifiReconnectTimer, 0);
    break;
  }
}

void onMqttConnect(bool currentSession)   //funzione di gestione evento connessione al server MQTT
{
  Serial.println("Connesso al server MQTT.");
  Serial.print("Sessione corrente: ");
  Serial.println(currentSession);
}

void onMqttDisconnect(AsyncMqttClientDisconnectReason reason)   //funzione di gestione evento disconnessione dal server MQTT
{
  while (mqttClient.connected())
  {
    Serial.print(".");
    delay(1000);
  }
  Serial.println("Disconnesso dal server MQTT.");
  if (WiFi.isConnected())   //controllo sullo stato della connessione del wi-fi
  {
    xTimerStart(mqttReconnectTimer, 0);   //tentativi di riconnessione con timer
  }
}

void onMqttSubscribe(uint16_t packetId, uint8_t qos)    //funzione di gestione accreditamento riuscito sul server
{
  Serial.println("Subscribe acknowledged.");
  Serial.print("  packetId: ");
  Serial.println(packetId);
  Serial.print("  QOS: ");
  Serial.println(qos);
}

void onMqttUnsubscribe(uint16_t packetId)   //funzione di gestione disaccreditamento dal server
{
  Serial.println("Unsubscribe acknowledged.");
  Serial.print("  packetId: ");
  Serial.println(packetId);
}

void onMqttMessage(char *topic, char *payload, AsyncMqttClientMessageProperties properties, size_t len, size_t index, size_t total)
{
  Serial.println("Publish ricevuto.");
}

int pubacks = 0;
long sendTime;
long returnTime;

void onMqttPublish(uint16_t packetId)
{
  returnTime = millis();
  ++pubacks;
  digitalWrite(LED_BUILTIN, HIGH);
  delay(2000);
  digitalWrite(LED_BUILTIN, LOW);
  delay(2000);
}

int button1Pin = 32;
int button2Pin = 35;
int led1Pin = 25;
int led2Pin = 26;

void setup()
{ 
  srand(time(NULL)); 
  Serial.begin(115200);
  Serial.println();
  Serial.println();
  pinMode(LED_BUILTIN, OUTPUT);
  pinMode(button1Pin, INPUT);
  pinMode(button2Pin, INPUT);
  pinMode(led1Pin, OUTPUT);
  pinMode(led2Pin, OUTPUT);

  digitalWrite(led1Pin, HIGH);
  digitalWrite(led2Pin, LOW);

  delay(1000);
  mqttReconnectTimer = xTimerCreate("mqttTimer", pdMS_TO_TICKS(1000), pdFALSE, (void *)0, reinterpret_cast<TimerCallbackFunction_t>(connectToMqtt));
  wifiReconnectTimer = xTimerCreate("wifiTimer", pdMS_TO_TICKS(2000), pdFALSE, (void *)0, reinterpret_cast<TimerCallbackFunction_t>(connectToWifi));

  WiFi.onEvent(WiFiEvent);

  //avvio servizi di connessione al server  MQTT

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
  if (mqttClient.connected()) //controllo se sono connesso al server
  {
    long start = millis();    //salvo l'istante iniziale
    bool randomize = false;

    Serial.println("Inviando 500 pacchetti con QOS0...");

    if(digitalRead(button1Pin) == HIGH){
      randomize = true;
      digitalWrite(led1Pin, HIGH);
      digitalWrite(led2Pin, LOW);
    }
    else if(digitalRead(button2Pin) == HIGH){
      randomize = false;
      digitalWrite(led1Pin, LOW);
      digitalWrite(led2Pin, HIGH);
    }

    int random_num = randomize ? (rand() % 2000) : 500;
    for (int i = 0; i < random_num; ++i)
    {
      mqttClient.publish("test", 0, false, "pack");   //invio di un pacchetto QOS0 (senza ricezione di acknowledgement) di test
    }

    mqttClient.publish("test", 0, false, "end");    //invio pacchetto finale

    long end = millis();    //salvo l'istante finale

    Serial.printf("Tempo impiegato %ld ms\n", (end - start));
    delay(1000);

    start = millis();   //salvo l'istante iniziale

    Serial.println("Inviando 500 pacchetti con QOS1...");
    for (int i = 0; i < random_num; ++i)
    {
      mqttClient.publish("test", 1, false, "pack");   //invio di un pacchetto QOS1 (con ricezione di acknowledgement)
    }

    end = millis();   //salvo l'istante finale

    Serial.printf("Tempo impiegato %ld ms\n", (end - start));
    delay(1000);

    Serial.printf("Ricevuti %i PUBACKs dopo 1 secondo.\n", pubacks);
    pubacks = 0;
  }
}