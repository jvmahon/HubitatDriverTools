library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Tools to Receive from Z-Wave Devices",
        name: "receiveTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "none",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/hubTools.groovy"
)

import java.util.concurrent.* 
import groovy.transform.Field

// create getUserParseMap() in driver to override.
Map getDefaultParseMap () {
	return [
		0x20:2, // Basic Set
		0x25:2, // Switch Binary
		0x26:4, // Switch MultiLevel 
		0x31:11, // Sensor MultiLevel
		0x32:6, // Meter
		0x5B:3,	// Central Scene
		0x60:4,	// MultiChannel
		0x62:1,	// Door Lock
		0x63:1,	// User Code
		0x6C:1,	// Supervision
		0x71:8, // Notification
		0x72: 1, // Manufacturer Specific
		0x80:3, // Battery
		0x86:3,	// Version
		0x98:1,	// Security
		0x9B:2,	// Configuration
		0x87:3  // Indicator
		]
}
		
////    Z-Wave Message Parsing   ////
// create userDefinedParseFilter to override
void parse(String description) {
		hubitat.zwave.Command cmd = zwave.parse(description, (userParseMap ?: defaultParseMap))
		if (userDefinedParseFilter) cmd = userDefinedParseFilter(cmd, description = null )
		if (cmd) { zwaveEvent(cmd) }
}

String secure(hubitat.zwave.Command cmd, ep = null ){ 
	if (ep) {
		return zwaveSecureEncap(zwave.multiChannelV4.multiChannelCmdEncap(sourceEndPoint: 0, bitAddress: 0, res01:0, destinationEndPoint: ep).encapsulate(cmd))
	} else {
		return zwaveSecureEncap(cmd) 
	}
}

// a simple unsupervised send with endpoint support
void basicZwaveSend(hubitat.zwave.Command cmd, ep = null ) { 
	sendHubCommand(new hubitat.device.HubAction( secure(cmd, ep), hubitat.device.Protocol.ZWAVE)) 
}

////    Security Encapsulation   ////
void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand( (userParseMap ?: defaultParseMap) )
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand) }
}

////    Multi-Channel Encapsulation   ////
void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    hubitat.zwave.Command  encapsulatedCommand = cmd.encapsulatedCommand((userParseMap ?: defaultParseMap))
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint) }
}


// This handles a supervised message (a "get") received from the Z-Wave device //
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, ep = null ) {
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand((userParseMap ?: defaultParseMap))
	
	hubitat.zwave.Command confirmation = (new hubitat.zwave.commands.supervisionv1.SupervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0))
	
	String confirmationMessage

    if (encapsulatedCommand) {
		if ( ep ) {
			zwaveEvent(encapsulatedCommand, ep)
			confirmationMessage =  zwaveSecureEncap(zwave.multiChannelV4.multiChannelCmdEncap(sourceEndPoint: 0, bitAddress: 0, res01:0, destinationEndPoint: ep).encapsulate(confirmation))

		} else {
			zwaveEvent(encapsulatedCommand)
			confirmationMessage = zwaveSecureEncap(confirmation) 
		}
    }
	sendHubCommand(new hubitat.device.HubAction(confirmationMessage, hubitat.device.Protocol.ZWAVE))
}

//// Catch Event Not Otherwise Handled! /////
void zwaveEvent(hubitat.zwave.Command cmd, ep = null ) {
    log.warn "Device ${device.displayName}: Received Z-Wave Message ${cmd} that is not handled by this driver. Endpoint: ${ep ?: 0}. Message class: ${cmd.class}."
}

