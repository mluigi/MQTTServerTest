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

#define WIFI_SSID "FASTWEB-tsT92f"
#define WIFI_PASSWORD "meloncello10"

#define MQTT_HOST IPAddress(192, 168, 1, 70)
#define MQTT_PORT 1883

AsyncMqttClient mqttClient;
TimerHandle_t mqttReconnectTimer;
TimerHandle_t wifiReconnectTimer;

void connectToWifi() //connessione esp al wi-fi
{
  Serial.println("Connettendo al Wi-Fi...");
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

void connectToMqtt() //funzione di connessione al server
{
  Serial.println("Connettendo al server MQTT...");
  mqttClient.setKeepAlive(4);
  mqttClient.setClientId("");
  mqttClient.setCleanSession(true);
  mqttClient.connect();
}

void WiFiEvent(WiFiEvent_t event) //funzione di gestione eventi wi-fi
{
  Serial.printf("[WiFi-event] evento: %d\n", event);
  switch (event)
  {
  case SYSTEM_EVENT_STA_GOT_IP: //indirizzo IP ottenuto
    Serial.println("WiFi connesso");
    Serial.println("Indirizzo IP: ");
    Serial.println(WiFi.localIP());
    connectToMqtt();
    break;
  case SYSTEM_EVENT_STA_DISCONNECTED: // connessione wi-fi persa
    Serial.println("WiFi: connessione persa.");
    xTimerStop(mqttReconnectTimer, 0); //tentativi di riconnessione con timer
    xTimerStart(wifiReconnectTimer, 0);
    break;
  }
}

void onMqttConnect(bool currentSession) //funzione di gestione evento connessione al server MQTT
{
  Serial.println("Connesso al server MQTT.");
  Serial.print("Sessione corrente: ");
  Serial.println(currentSession);
}

int totQOS0 = 0;
int totQOS1 = 0;
int pubacks = 0;

void onMqttDisconnect(AsyncMqttClientDisconnectReason reason) //funzione di gestione evento disconnessione dal server MQTT
{
  totQOS0 = 0;
  totQOS1 = 0;
  pubacks = 0;
  while (mqttClient.connected())
  {
    Serial.print(".");
    delay(1000);
  }
  Serial.println("Disconnesso dal server MQTT.");
  if (WiFi.isConnected()) //controllo sullo stato della connessione del wi-fi
  {
    xTimerStart(mqttReconnectTimer, 0); //tentativi di riconnessione con timer
  }
}

void onMqttSubscribe(uint16_t packetId, uint8_t qos) //funzione di gestione accreditamento riuscito sul server
{
  Serial.println("Subscribe acknowledged.");
  Serial.print("  packetId: ");
  Serial.println(packetId);
  Serial.print("  QOS: ");
  Serial.println(qos);
}

void onMqttUnsubscribe(uint16_t packetId) //funzione di gestione disaccreditamento dal server
{
  Serial.println("Unsubscribe acknowledged.");
  Serial.print("  packetId: ");
  Serial.println(packetId);
}

void onMqttMessage(char *topic, char *payload, AsyncMqttClientMessageProperties properties, size_t len, size_t index, size_t total)
{
  Serial.println("Publish ricevuto.");
}

void onMqttPublish(uint16_t packetId)
{
  ++pubacks;
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

  digitalWrite(led2Pin, HIGH);
  digitalWrite(led1Pin, LOW);

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

bool randomize = false;
bool running = true;
char buf[128];
void loop()
{
  if (mqttClient.connected()) //controllo se sono connesso al server
  {
    long start = millis(); //salvo l'istante iniziale

    if (digitalRead(button1Pin) == HIGH)
    {
      randomize = !randomize;
      if (randomize)
      {
        digitalWrite(led1Pin, HIGH);
        digitalWrite(led2Pin, LOW);
      }
      else
      {
        digitalWrite(led1Pin, LOW);
        digitalWrite(led2Pin, HIGH);
      }

      Serial.println("Premuto primo pulsante");
    }
    else if (digitalRead(button2Pin) == HIGH)
    {
      running = !running;
      if (running)
      {
        if (randomize)
        {
          digitalWrite(led1Pin, HIGH);
        }
        else
        {
          digitalWrite(led2Pin, HIGH);
        }
      }
      else
      {
        digitalWrite(led1Pin, LOW);
        digitalWrite(led2Pin, LOW);
        delay(1000);
      }

      Serial.println("Premuto secondo pulsante");
    }

if(running){
    int random_num = randomize ? (rand() % 2000) : 500;
    Serial.printf("Inviando %d pacchetti con QoS = 0...", random_num);
    for (int i = 0; i < random_num; ++i)
    {
      totQOS0++;
      snprintf(buf, sizeof(buf), "%d,%d,%d", totQOS0, totQOS1, pubacks);
      //invio di un pacchetto QOS0 (senza ricezione di acknowledgement) di test
      //con all'interno il totale dei messaggi mandati così che il server possa fare il confronto
      //tra i messaggi mandati e quelli effettivamente ricevuti
      mqttClient.publish("test", 0, false, buf);
    }
    long end = millis(); //salvo l'istante finale

    Serial.printf("Tempo impiegato %ld ms\n", (end - start));
    delay(1000);

    start = millis(); //salvo l'istante iniziale

    Serial.printf("Inviando %d pacchetti con QoS = 1...", random_num);
    for (int i = 0; i < random_num; ++i)
    {
      totQOS1++;
      snprintf(buf, sizeof(buf), "%d,%d,%d", totQOS0, totQOS1, pubacks);
      mqttClient.publish("test", 1, false, buf); //invio di un pacchetto QOS1 (con ricezione di acknowledgement)
    }

    end = millis(); //salvo l'istante finale

    Serial.printf("Tempo impiegato %ld ms\n", (end - start));
    delay(1000);

    Serial.printf("Totale messaggi mandati: QOS0 %d, QOS1 %d, Totale Puback ricevuti %d\n", totQOS0, totQOS1, pubacks);
  }
  }
}