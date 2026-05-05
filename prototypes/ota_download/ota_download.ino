/*
  OTA

  This sketch demonstrates how to make an OTA Update on the UNO R4 WiFi.
  Upload the sketch and wait for the invasion!

*/


#include "WiFiS3.h"
#include "OTAUpdate.h"
#include "root_ca.h"
#include "arduino_secrets.h"

char ssid[] = SECRET_SSID;    // your network SSID (name)
char pass[] = SECRET_PASS;    // your network password (use for WPA, or use as key for WEP)

int status = WL_IDLE_STATUS;

OTAUpdate ota;
// Direct raw URL — no redirect, single Let's Encrypt cert chain.
// (Earlier release-asset URL failed with -26 because release downloads
// 302-redirect to objects.githubusercontent.com which has a different cert
// chain than github.com itself, and the modem may not follow redirects.)
static char const OTA_FILE_LOCATION[] = "https://raw.githubusercontent.com/mrmees/ms3pnp-canbus-expander/main/firmware-releases/animation-test.ota";

/* -------------------------------------------------------------------------- */
void setup() {
/* -------------------------------------------------------------------------- */
  //Initialize serial and wait for port to open:
  Serial.begin(115200);
  while (!Serial) {
    ; // wait for serial port to connect. Needed for native USB port only
  }

  // check for the Wi-Fi module:
  if (WiFi.status() == WL_NO_MODULE) {
    Serial.println("Communication with Wi-Fi module failed!");
    // don't continue
    while (true);
  }

  String fv = WiFi.firmwareVersion();
  if (fv < WIFI_FIRMWARE_LATEST_VERSION) {
    Serial.println("Please upgrade the firmware");
  }

  // attempt to connect to Wi-Fi network:
  while (status != WL_CONNECTED) {
    Serial.print("Attempting to connect to SSID: ");
    Serial.println(ssid);
    // Connect to WPA/WPA2 network. Change this line if using open or WEP network:
    status = WiFi.begin(ssid, pass);

    // wait 1 seconds for connection:
    delay(1000);
  }

  printWiFiStatus();

  int ret = ota.begin("/update.bin");
  if(ret != OTAUpdate::OTA_ERROR_NONE) {
    Serial.println("ota.begin() error: ");
    Serial.println((int)ret);
    return;
  }
  ret = ota.setCACert(root_ca);
  if(ret != OTAUpdate::OTA_ERROR_NONE) {
    Serial.println("ota.setCACert() error: ");
    Serial.println((int)ret);
    return;
  }
  // NON-BLOCKING download. Host timeout for the blocking variant is 60s
  // (EXTENDED_MODEM_TIMEOUT); slow cell hotspot + RSA-4096 ISRG handshake
  // can easily exceed that. startDownload() kicks off and returns; we poll.
  Serial.print("ota.startDownload() from: ");
  Serial.println(OTA_FILE_LOCATION);
  int rc_start = ota.startDownload(OTA_FILE_LOCATION, "/update.bin");
  Serial.print("startDownload() returned: ");
  Serial.println(rc_start);
  if(rc_start < 0) {
    Serial.println("startDownload FAILED");
    return;
  }
  // Poll progress every 5s until done or stuck. Bridge spec says
  // downloadProgress() returns bytes-downloaded-so-far, with the value
  // settling once download completes. We give it 6 minutes max.
  int last_p = -1;
  unsigned long t_start = millis();
  while (millis() - t_start < 360000UL) {
    delay(5000);
    int p = ota.downloadProgress();
    Serial.print("  progress: ");
    Serial.print(p);
    Serial.print(" (elapsed ");
    Serial.print((millis()-t_start)/1000);
    Serial.println("s)");
    if (p < 0) { Serial.println("downloadProgress error"); return; }
    if (p == last_p && p > 0) {
      // Progress stopped advancing; assume done.
      Serial.println("progress stable -> assume complete");
      break;
    }
    last_p = p;
  }
  int ota_size = ota.downloadProgress();
  Serial.print("final size: ");
  Serial.println(ota_size);
  if (ota_size <= 0) {
    Serial.println("download FAILED — final size <= 0");
    return;
  }
  ret = ota.verify();
  if(ret != OTAUpdate::OTA_ERROR_NONE) {
    Serial.println("ota.verify() error: ");
    Serial.println((int)ret);
    return;
  }

  ret = ota.update("/update.bin");
  if(ret != OTAUpdate::OTA_ERROR_NONE) {
    Serial.println("ota.update() error: ");
    Serial.println((int)ret);
    return;
  }
}

/* -------------------------------------------------------------------------- */
void loop() {
/* -------------------------------------------------------------------------- */
  delay(1000);
}

/* -------------------------------------------------------------------------- */
void printWiFiStatus() {
/* -------------------------------------------------------------------------- */
  // print the SSID of the network you're attached to:
  Serial.print("SSID: ");
  Serial.println(WiFi.SSID());

  // print your board's IP address:
  IPAddress ip = WiFi.localIP();
  Serial.print("IP Address: ");
  Serial.println(ip);

  // print the received signal strength:
  long rssi = WiFi.RSSI();
  Serial.print("signal strength (RSSI):");
  Serial.print(rssi);
  Serial.println(" dBm");
}
