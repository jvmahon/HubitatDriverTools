library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handle Interactions with the Hubitat Hub",
        name: "batteryTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "",
		librarySource:""
)
void zwaveEvent(hubitat.zwave.commands.batteryv3.BatteryReport cmd) { processBatteryEvent(cmd) }
void zwaveEvent(hubitat.zwave.commands.batteryv3.BatteryReport cmd)  { processBatteryEvent(cmd) }
void zwaveEvent(hubitat.zwave.commands.batteryv3.BatteryReport cmd)  { processBatteryEvent(cmd) }
void processBatteryEvent(cmd) 
{
	// In Z-Wave, battery is only reported for the 'root' device, but if there are child devices with a battery attribute, update them too!
	List<com.hubitat.app.DeviceWrapper> batteryDevices =  (getChildDevices() + device).findAll{it -> it.hasAttribute("battery")}
	Map batteryEvent	
	if (cmd.batteryLevel == 0xFF) {
		batteryEvent = [name: "battery", value:1, unit: "%", descriptionText: "Low Battery Alert. Change now!", deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]
	} else {
		batteryEvent = [name: "battery", value:cmd.batteryLevel, unit: "%", descriptionText: "Battery level report.", deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]
	}
	batteryDevices.each{ it -> it.sendEvent ( batteryEvent)}	
}

void refreshBattery() {
	if (isZwaveListening() ) { sendUnsupervised(zwave.batteryV1.batteryGet()) } 
		else {
		log.warn "Device ${device.displayName}: Called batteryRefresh on a non-listening node. Code should be updated to add the refresh to a pending command queue when the device wakes up!"
		}
}