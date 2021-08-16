library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handle Interactions with the Hubitat Hub",
        name: "batteryTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
        version:"0.0.1",
		dependencies: "",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/batteryTools.groovy"

)


void zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) { processBatteryEvent(cmd) }
void zwaveEvent(hubitat.zwave.commands.batteryv2.BatteryReport cmd)  { processBatteryEvent(cmd) }
void zwaveEvent(hubitat.zwave.commands.batteryv3.BatteryReport cmd)  { processBatteryEvent(cmd) }
void processBatteryEvent(cmd) 
{
	if (cmd.batteryLevel == 0xFF) {
		batteryEvent = [name: "battery", value:1, unit: "%", descriptionText: "Low Battery Alert. Change now!", deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]
	} else {
		batteryEvent = [name: "battery", value:cmd.batteryLevel, unit: "%", descriptionText: "Battery level report."]
	}
	sendEventToAll(event:batteryEvent, alwaysSend:["battery"])
}

void batteryTools_refreshBattery() {
	if (isZwaveListening() ) { advancedZwaveSend(zwave.batteryV1.batteryGet()) 
		} else {
		log.warn "Device ${device.displayName}: Called batteryRefresh on a non-listening node. Code should be updated to add the refresh to a pending command queue when the device wakes up!"
		}
}
