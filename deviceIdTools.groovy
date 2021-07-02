library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "A set of tools to handle identification of devices such as by flashing a LED on the device.",
        name: "deviceIdTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.hubTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/sleepyTools.groovy"
)


void deviceIdTools_identify() {
	log.warn "Device ${device.displayName}: The 'identify' function is experimental and only works for Zwave Plus Version 2 or greater devices!"
	// Identify function supported by Zwave Plus Version 2 and greater devices!
		List<Map<String, Short>> indicators = [
			[indicatorId:0x50, propertyId:0x03, value:0x08], 
			[indicatorId:0x50, propertyId:0x04, value:0x03],  
			[indicatorId:0x50, propertyId:0x05, value:0x06]
		]
		sendUnsupervised(zwave.indicatorV3.indicatorSet(indicatorCount:3 , value:0, indicatorValues: indicators ))
}
