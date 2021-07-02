library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "A set of tools to handle devices that sleep",
        name: "sleepyTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/sleepyTools.groovy"
)

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
log.debug "Received WakeUpIntervalReport message: ${cmd}"

}

void zwaveEvent(hubitat.zwave.commands.wakeupv3.WakeUpNotification cmd) {
log.debug "Received WakeUpNotification message: ${cmd}"

}

void sendNoMoreInfo() {
	sendUnsupervised(zwave.wakeUpV2.wakeUpNoMoreInformation())
}