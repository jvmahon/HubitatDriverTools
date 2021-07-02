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
