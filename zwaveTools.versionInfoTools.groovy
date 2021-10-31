library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handle Interactions with the Hubitat Hub",
        name: "versionInfoTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
        version:"0.0.1",
		dependencies: "none",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/versionInfoTools.groovy"

)

void zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd)  { 
    log.info "Version report: ${cmd}"
	String thisDeviceVersion 
	thisDeviceVersion = "${cmd.firmware0Version}." + "${cmd.firmware0SubVersion}".padLeft(3,"0")
	updateDataValue("mainFirmwareVersion", thisDeviceVersion)
	updateDataValue("hardwareVersion", "${cmd.hardwareVersion}")
}

void versionInfoTools_refreshVersionInfo() {
	advancedZwaveSend(zwave.versionV3.versionGet()) 
}

void zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv6.FirmwareUpdateMdGet cmd)  { 
	log.info "Device ${device.displayName}: Firmware Update in Progress. Report No. ${cmd.reportNumber}"  
}

void zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv6.FirmwareUpdateMdRequestReport cmd)  { 
		String statusReport = [
		0x01:"ERROR. Invalid combination of Manufacturer ID and Firmware ID. The device will not initiate the firmware update.",
		0x02:"ERROR. Device expected an authentication event to enable firmware update. The device will not initiate the firmware update.",
		0x03:"ERROR. The requested Fragment Size exceeds the Max Fragment Size. The device will not initiate the firmware update.",
		0x04:"ERROR. This firmware target is not upgradable. The device will not initiate the firmware update.",
		0x05:"ERROR. Invalid Hardware Version. The device will not initiate the firmware update.",
		0x06:"ERROR: Another firmware image is current being transferred. The device will not initiate the firmware update.",
		0x07:"ERROR: Insufficient battery level. The device will not initiate the firmware update.",
		0x08:"OK. The device will initiate the firmware update of the target specified in the Firmware Update Meta Data Request Get Command."
		].get((int) cmd.status)

		log.info "Device ${device.displayName}: Firmware Update Report: ${statusReport}"  
}

void zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv6.FirmwareMdReport cmd)  { 
	log.info "Device ${device.displayName}: Firmware Update in Progress. Received FirmwareMdReport: ${cmd}"  
}

void refreshManufacturerSpecificInfo() {
	advancedZwaveSend(zwave.manufacturerSpecificV2.manufacturerSpecificGet()) 

}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd)  { 
	if (txtEnable) log.info "Received ManufacturerSpecificReport: ${cmd}"
	updateDataValue("deviceType", "${cmd.productTypeId}")
	updateDataValue("deviceId", "${cmd.productId}")
	updateDataValue("manufacturer", "${cmd.manufacturerId}")
	
}