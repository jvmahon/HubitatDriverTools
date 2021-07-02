
metadata {
	definition (name: "Wake Up Testing Tools",namespace: "jvm", author: "jvm") {

		command "getWakeUpIntervalReport" 
		command "setWakeUpInterval", 	[[name:"time",type:"NUMBER", description:"WakeUp Seconds"]]	
		command "getWakeUpIntervalCapabilitiesGet"
		
    }
	
	preferences 
	{	
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
		input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true
    }	
}

Map getDefaultParseMap()
{
	return [0x84:3]
}
#include zwaveTools.hubTools

void setWakeUpInterval(time){
	log.debug "Setting Wake Up Interval to time ${time}"
	sendToDevice(zwave.wakeUpV2.wakeUpIntervalSet(nodeid:zwaveHubNodeId, seconds:(time as Integer)))
	getWakeUpIntervalReport()
}

void sendToDevice(hubitat.zwave.Command cmd) { 
	sendHubCommand(new hubitat.device.HubAction( zwaveSecureEncap(cmd) , hubitat.device.Protocol.ZWAVE)) 
}


void queueCommand(hubitat.zwave.Command cmd, ep = null) {
}

void unQueueCommand(hubitat.zwave.Command cmd, ep = null ) {
}
void clearQueue(ep = null ) {
}

void processPendingQueue(){
}

Boolean isPendingCommands(){
}

Boolean setWakePeriod(){
}

void zwaveEvent(hubitat.zwave.commands.wakeupv3.WakeUpIntervalCapabilitiesReport cmd) {

log.debug "Received WakeUpIntervalCapabilitiesReport message: ${cmd}"
}

void zwaveEvent(hubitat.zwave.commands.wakeupv3.WakeUpIntervalReport cmd) {
log.debug "Received WakeUpIntervalReport message: ${cmd}. node ID should be ${zwaveHubNodeId}."

}

void zwaveEvent(hubitat.zwave.commands.wakeupv3.WakeUpNotification cmd) {
Date date = new Date()
log.debug "Received WakeUpNotification message: ${cmd} at time ${date}"

sendEvent([name:"lastWakeTime", value:date ])

}
void getWakeUpIntervalReport() {
	sendToDevice(zwave.wakeUpV2.wakeUpIntervalGet())
}

void sendNoMoreInfo() {
	sendToDevice(zwave.wakeUpV2.wakeUpNoMoreInformation())
}

void getWakeUpIntervalCapabilitiesGet() {
	sendToDevice(zwave.wakeUpV2.wakeUpIntervalCapabilitiesGet())
}
