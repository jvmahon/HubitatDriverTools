library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handle Interactions with the Hubitat Hub",
        name: "batteryTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
        version:"0.0.1",
		dependencies: "none",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/batteryTools.groovy"

)

void zwaveEvent(hubitat.zwave.commands.batteryv3.BatteryReport cmd)  { 
	if (cmd.batteryLevel == 0xFF) {
		batteryEvent = [name: "battery", value:1, unit: "%", descriptionText: "Low Battery Alert 1%. Change now!"]
	} else {
		batteryEvent = [name: "battery", value:cmd.batteryLevel, unit: "%", descriptionText: "Battery level ${cmd.batteryLevel}%."]
	}
	(childDevices + device).each{ it.sendEvent(batteryEvent) }
}

void batteryTools_refreshBattery() {
	advancedZwaveSend(zwave.batteryV1.batteryGet()) 
}
