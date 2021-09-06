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
		batteryEvent = [name: "battery", value:1, unit: "%", descriptionText: "Low Battery Alert. Change now!"]
	} else {
		batteryEvent = [name: "battery", value:cmd.batteryLevel, unit: "%", descriptionText: "Battery level report."]
	}
	sendEventToAll(event:batteryEvent, alwaysSend:["battery"])
}

void batteryTools_refreshBattery() {
	advancedZwaveSend(zwave.batteryV1.batteryGet()) 
}
