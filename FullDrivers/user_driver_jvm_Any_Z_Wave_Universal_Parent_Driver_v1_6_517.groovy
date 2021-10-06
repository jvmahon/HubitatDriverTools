import java.util.concurrent.* // Available (allow-listed) concurrency classes: ConcurrentHashMap, ConcurrentLinkedQueue, Semaphore, SynchronousQueue
import groovy.transform.Field

//////////////

void supervisionCheckResendInfo(report){
	log.warn "Device ${device.displayName}: Supervision Check is resending command: ${report.command} for endpoint ${report.endPoint}, previous attempts: ${report.attempt}."
}
Integer getS2MaxRetries() { return 5 }
Integer getS2RetryPeriod() { return 1500}
/////////// 












/////////////////


metadata {
	definition (name: "Any Z-Wave Universal Parent Driver v1.6",namespace: "jvm", author: "jvm", singleThreaded:false) {
		capability "Initialize"
		capability "Refresh"
	
	// Uncomment capabilities that you want to expose in the parent.
	// Otherwise, all capabilities / attributes are by adding child devices.
        // capability "Actuator"
		// capability "Switch"
		// capability "SwitchLevel"		

		capability "PushableButton"
		capability "HoldableButton"
		capability "ReleasableButton"
		capability "DoubleTapableButton"	
		attribute "multiTapButton", "number"
        // capability "Sensor"				
        // capability "MotionSensor"
        // capability "TamperAlert"
		// capability "WaterSensor"
		// capability "ContactSensor"
		// capability "ShockSensor"		// Use this for glass breakage!
		// capability "IllumanceMeasurement"
		// capability "LiquidFlowRate"
		// attribute "carbonDioxideDetected"
		

		
		// capability "Battery"

		// capability "Consumable" 		// For smoke, CO, CO2 alarms that report their end-of-life
		// capability "FilterStatus" 	// For water filters that report status of filter
		

		command "identify" // implements the Z-Wave Plus identify function which can flash device indicators.
		command "resetDriver" // deletes the stored state information

		command "addNewChildDevice", [[name:"Device Name*", type:"STRING"], 
                                      [name:"componentDriverName*",type:"ENUM", constraints:(getDriverChoices()) ], 
                                      [name:"Endpoint",type:"NUMBER", description:"Endpoint Number, blank or 0 = root" ] ]

  
        command "multiTap", [[name:"button",type:"NUMBER", description:"Button Number", constraints:["NUMBER"]],
		 			[name:"taps",type:"NUMBER", description:"Tap count", constraints:["NUMBER"]]]								
		command "setParameter",[[name:"parameterNumber",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]],
					[name:"value",type:"NUMBER", description:"Parameter Value", constraints:["NUMBER"]]
					]	

		// Following Command is to help create a new data record to be added to deviceDatabase
       //  command "logDataRecord"

    }
	
	preferences 
	{	
        input name: "showParameterInputs", type: "bool", title: "Show Parameter Value Input Controls", defaultValue: false    
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
		input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true

		if (showParameterInputs) {
			getParameterValuesFromDevice()
			deviceInputs?.each{key, value -> input value}
        }

    }	
}
/////////////////////////////////////////////////////////////////////////
//////        Create and Manage Child Devices for Endpoints       ///////
/////////////////////////////////////////////////////////////////////////

void logDataRecord() {
log.debug dataRecordByProductType
}


/////////////////////////////////////////////////////////////////

void identify() {
	log.warn "Device ${device.displayName}: The 'identify' function is experimental and only works for Zwave Plus Version 2 or greater devices!"
	// Identify function supported by Zwave Plus Version 2 and greater devices!
		List<Map<String, Short>> indicators = [
			[indicatorId:0x50, propertyId:0x03, value:0x08], 
			[indicatorId:0x50, propertyId:0x04, value:0x03],  
			[indicatorId:0x50, propertyId:0x05, value:0x06]
		]
		advancedZwaveSend(zwave.indicatorV3.indicatorSet(indicatorCount:3 , value:0, indicatorValues: indicators ))
}


void resetDriver() {
	state.clear()
}

void clearLeftoverStates() {
	List<String> allowed = ["deviceRecord"] 
	
	// Can't modify state from within state.each{}, so first collect what is unwanted, then remove in a separate unwanted.each
	List<String> unwanted = state.collect{ 
			if (allowed.contains( it.key as String)) return
			return it.key
		}.each{state.remove( it ) }
}

void removeAllSettings() {
    if (logEnable) log.debug "settings before clearing: " + settings
    // Copy keys set first to avoid any chance of concurrent modification
    def keys = new HashSet(settings.keySet())
    keys.each{ key -> device.removeSetting(key) }
     if (logEnable) log.debug "settings after clearing: " + settings
}

void initialize()
{
	// removeAllSettings()
	// By default, hide the parameter settings inputs since displaying them forces a refresh of all values the first time they are shown and is time consuming!
    device.updateSetting("showParameterInputs",[value:"false",type:"bool"])

	clearLeftoverStates()
	log.info "Device ${device.displayName}: Initializing."

	/////////////////////////////////////////////////////////////////////////////////////
	///                      Don't Alter this code block code!                        ///
	/// This code manages the different ways in which the device record may be stored ///
	///             - i.e., locally or from the openSmartHouse database               ///
	/////////////////////////////////////////////////////////////////////////////////////
	// If the format of the device record has changed, delete any locally stored data and recreate 
	if ((state.deviceRecord?.formatVersion as Integer) != dataRecordFormatVersion) state.remove("deviceRecord")
	
	Map localDataRecord = getThisDeviceDatabaseRecord()
	if (localDataRecord && (localDataRecord.formatVersion != dataRecordFormatVersion)) {
		log.warn "Device ${device.displayName}: Locally stored data record has wrong version number and will be ignored. Obtaining data from openSmartHouse instead. Locally stored record is: ${localDataRecord.inspect()}."
		}
		
	if (localDataRecord && (localDataRecord.formatVersion == dataRecordFormatVersion)){
		state.remove("deviceRecord") // If a device data record was added to the database, delete if it was previously from openSmartHouse.
		dataRecordByProductType.putAll(reparseDeviceData(localDataRecord)) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord && getDataRecordByProductType().deviceRecord.is( null ) ) { 
		// Put in the Global ConcurrentHashMap if it exist locally.
		dataRecordByProductType.putAll(reparseDeviceData(localDataRecord)) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord.is( null ) && getDataRecordByProductType().deviceRecord ) {
		// Data record doesn't exist in state, but it is in the concurrentHashMap - So store in state rather than re-retrieve
		state.deviceRecord = dataRecordByProductType.deviceRecord
	} else if ( state.deviceRecord.is( null )) {
		// Data record doesn't exist - get it and store in the global data record
		Map createdRecord = openSmarthouseCreateDeviceDataRecord() 
		state.deviceRecord = createdRecord
		if (createdRecord) dataRecordByProductType.putAll(reparseDeviceData(localDataRecord))
	}
	///////////////////////////////////////////////////////////////////////////////////
	//////////          Done with Device Data Record Management      //////////////////
	///////////////////////////////////////////////////////////////////////////////////	
	List<Integer> supportedClasses = getThisEndpointClasses(ep)
	
	// Create child devices if this is a multi-channel device.
	if (getDataRecordByProductType().classVersions?.containsKey(0x60)) {
		deleteUnwantedChildDevices()
		createChildDevices()
		}

	if (getDataRecordByProductType().classVersions?.containsKey(0x5B)) advancedZwaveSend(zwave.centralSceneV3.centralSceneSupportedGet())
	if (getDataRecordByProductType().classVersions?.containsKey(0x6C)) sendInitialCommand()
	
	if (txtEnable) log.info "Device ${device.displayName}: Refreshing device data."
	refresh()  
	
	if (txtEnable) log.info "Device ${device.displayName}: Done Initializing."

}

//////////// Get Inputs //////////////

Map getDeviceInputs()  { 
	Map returnMe = getDataRecordByProductType()?.deviceInputs?.sort({it.key})
	if (logEnable && returnMe.is( null ) ) log.warn "Device ${device.displayName}: Device has no inputs. Check if device was initialized. returnMe is ${returnMe}."
	return returnMe
}

Map filteredDeviceInputs() {
	if (advancedEnable) { 
		return getDeviceInputs()?.sort()
	} else  { // Just show the basic items
		return 	getDeviceInputs()?.findAll { it.value.category != "advanced" }?.sort()
	}
}

///////////////////////////////////////////////////////////////////////////////////////
///////      Handle Refreshes      ////////
/////////////////////////////////////////////////////////////////////////////////////// 
void componentRefresh(com.hubitat.app.DeviceWrapper cd){
	refreshEndpoint(cd:cd)
}

void refreshEndpoint(Map params = [cd: null, ep: null ])
{
	// com.hubitat.app.DeviceWrapper targetDevice = device
	Integer ep = null
	if (params.cd) {
			ep = (params.cd.deviceNetworkId.split("-ep")[-1]) as Integer
	} else if (! params.ep.is( null )) {
		ep = params.ep as Integer
	}
	if (ep.is( null )) return
	
	Map record = getThisEndpointData(ep)
		if (logEnable) log.debug "Device ${device.displayName}: Refreshing endpoint: ${ep ?: 0} with record ${record}"
		if (txtEnable) log.info "Device ${device.displayName}: refreshing values for endpoint ${ep}."
		List<Integer> supportedClasses = getThisEndpointClasses(ep)
		if (supportedClasses.contains(0x25)) 		advancedZwaveSend(zwave.switchBinaryV1.switchBinaryGet(), ep)
		if (supportedClasses.contains(0x26)) 		advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelGet(), ep)
		if (supportedClasses.contains(0x32) && meterTools_refresh) 			meterTools_refresh(ep)
		if (supportedClasses.contains(0x71) && notificationTools_refresh ) 	notificationTools_refresh(ep)
		// if (record.classes.contains(0x62)) 		refreshLock(ep)
		if (supportedClasses.contains(0x80)) 		batteryTools_refreshBattery()
}

void refresh()
{
	getFullEndpointRecord().each{thisEp, v ->
		refreshEndpoint(ep:thisEp)
	}
}

void	refreshLock(ep = null ) {
	log.error "Device ${device.displayName} Function refreshLock is not fully implemented."
}

//////////////////////////////////////////////////////////////////////
//////                  Z-Wave Helper Functions                ///////
//////   Format messages, Send to Device, secure Messages      ///////
//////////////////////////////////////////////////////////////////////

void logsOff() {
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

////    Hail   ////
void zwaveEvent(hubitat.zwave.commands.hailv1.Hail cmd) {
	refresh()
}

// ~~~~~ start include (35) zwaveTools.sendReceiveTools ~~~~~
library ( // library marker zwaveTools.sendReceiveTools, line 1
        base: "driver", // library marker zwaveTools.sendReceiveTools, line 2
        author: "jvm33", // library marker zwaveTools.sendReceiveTools, line 3
        category: "zwave", // library marker zwaveTools.sendReceiveTools, line 4
        description: "Tools to Send and Receive from Z-Wave Devices", // library marker zwaveTools.sendReceiveTools, line 5
        name: "sendReceiveTools", // library marker zwaveTools.sendReceiveTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.sendReceiveTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.sendReceiveTools, line 8
		version: "0.0.1", // library marker zwaveTools.sendReceiveTools, line 9
		dependencies: "none", // library marker zwaveTools.sendReceiveTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/sendReceiveTools.groovy" // library marker zwaveTools.sendReceiveTools, line 11
) // library marker zwaveTools.sendReceiveTools, line 12

import java.util.concurrent.*  // library marker zwaveTools.sendReceiveTools, line 14
import groovy.transform.Field // library marker zwaveTools.sendReceiveTools, line 15

// create getUserParseMap() in driver to override. // library marker zwaveTools.sendReceiveTools, line 17
Map getDefaultParseMap () { // library marker zwaveTools.sendReceiveTools, line 18
	return [ // library marker zwaveTools.sendReceiveTools, line 19
		0x20:2, // Basic Set // library marker zwaveTools.sendReceiveTools, line 20
		0x25:2, // Switch Binary // library marker zwaveTools.sendReceiveTools, line 21
		0x26:4, // Switch MultiLevel  // library marker zwaveTools.sendReceiveTools, line 22
		0x31:11, // Sensor MultiLevel // library marker zwaveTools.sendReceiveTools, line 23
		0x32:6, // Meter // library marker zwaveTools.sendReceiveTools, line 24
		0x5B:3,	// Central Scene // library marker zwaveTools.sendReceiveTools, line 25
		0x60:4,	// MultiChannel // library marker zwaveTools.sendReceiveTools, line 26
		0x62:1,	// Door Lock // library marker zwaveTools.sendReceiveTools, line 27
		0x63:1,	// User Code // library marker zwaveTools.sendReceiveTools, line 28
		0x6C:1,	// Supervision // library marker zwaveTools.sendReceiveTools, line 29
		0x71:8, // Notification // library marker zwaveTools.sendReceiveTools, line 30
		0x72: 1, // Manufacturer Specific // library marker zwaveTools.sendReceiveTools, line 31
		0x80:3, // Battery // library marker zwaveTools.sendReceiveTools, line 32
		0x86:3,	// Version // library marker zwaveTools.sendReceiveTools, line 33
		0x98:1,	// Security // library marker zwaveTools.sendReceiveTools, line 34
		0x9B:2,	// Configuration // library marker zwaveTools.sendReceiveTools, line 35
		0x87:3  // Indicator // library marker zwaveTools.sendReceiveTools, line 36
		] // library marker zwaveTools.sendReceiveTools, line 37
} // library marker zwaveTools.sendReceiveTools, line 38

////    Z-Wave Message Parsing   //// // library marker zwaveTools.sendReceiveTools, line 40
// create userDefinedParseFilter to override // library marker zwaveTools.sendReceiveTools, line 41
void parse(String description) { // library marker zwaveTools.sendReceiveTools, line 42
		hubitat.zwave.Command cmd = zwave.parse(description, (userParseMap ?: defaultParseMap)) // library marker zwaveTools.sendReceiveTools, line 43
		if (userDefinedParseFilter) cmd = userDefinedParseFilter(cmd, description) // library marker zwaveTools.sendReceiveTools, line 44
		if (cmd) { zwaveEvent(cmd) } // library marker zwaveTools.sendReceiveTools, line 45
} // library marker zwaveTools.sendReceiveTools, line 46



void parse(List<Map> listOfEvents) { // library marker zwaveTools.sendReceiveTools, line 50
    listOfEvents.each { // library marker zwaveTools.sendReceiveTools, line 51
        if (device.hasAttribute(it.name)) { // library marker zwaveTools.sendReceiveTools, line 52
            if (txtEnable && it.descriptionText) log.info it.descriptionText // library marker zwaveTools.sendReceiveTools, line 53
            sendEvent(it) // library marker zwaveTools.sendReceiveTools, line 54
        } // library marker zwaveTools.sendReceiveTools, line 55
    } // library marker zwaveTools.sendReceiveTools, line 56
} // library marker zwaveTools.sendReceiveTools, line 57

String secure(hubitat.zwave.Command cmd, Integer ep = null ){  // library marker zwaveTools.sendReceiveTools, line 59
	if (ep) { // library marker zwaveTools.sendReceiveTools, line 60
		return zwaveSecureEncap(zwave.multiChannelV4.multiChannelCmdEncap(sourceEndPoint: 0, bitAddress: 0, res01:0, destinationEndPoint: ep).encapsulate(cmd)) // library marker zwaveTools.sendReceiveTools, line 61
	} else { // library marker zwaveTools.sendReceiveTools, line 62
		return zwaveSecureEncap(cmd)  // library marker zwaveTools.sendReceiveTools, line 63
	} // library marker zwaveTools.sendReceiveTools, line 64
} // library marker zwaveTools.sendReceiveTools, line 65

// a simple unsupervised send with endpoint support // library marker zwaveTools.sendReceiveTools, line 67
void basicZwaveSend(hubitat.zwave.Command cmd, Integer ep = null ) {  // library marker zwaveTools.sendReceiveTools, line 68
	sendHubCommand(new hubitat.device.HubAction( secure(cmd, ep), hubitat.device.Protocol.ZWAVE))  // library marker zwaveTools.sendReceiveTools, line 69
} // library marker zwaveTools.sendReceiveTools, line 70

////    Security Encapsulation   //// // library marker zwaveTools.sendReceiveTools, line 72
void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) { // library marker zwaveTools.sendReceiveTools, line 73
	hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand( (userParseMap ?: defaultParseMap) ) // library marker zwaveTools.sendReceiveTools, line 74
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand) } // library marker zwaveTools.sendReceiveTools, line 75
} // library marker zwaveTools.sendReceiveTools, line 76

////    Multi-Channel Encapsulation   //// // library marker zwaveTools.sendReceiveTools, line 78
void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) { // library marker zwaveTools.sendReceiveTools, line 79
    hubitat.zwave.Command  encapsulatedCommand = cmd.encapsulatedCommand((userParseMap ?: defaultParseMap)) // library marker zwaveTools.sendReceiveTools, line 80
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint) } // library marker zwaveTools.sendReceiveTools, line 81
} // library marker zwaveTools.sendReceiveTools, line 82


//// Catch Event Not Otherwise Handled! ///// // library marker zwaveTools.sendReceiveTools, line 85
void zwaveEvent(hubitat.zwave.Command cmd, Integer ep = null ) { // library marker zwaveTools.sendReceiveTools, line 86
    log.warn "Device ${device.displayName}: Received Z-Wave Message ${cmd} that is not handled by this driver. Endpoint: ${ep ?: 0}. Message class: ${cmd.class}." // library marker zwaveTools.sendReceiveTools, line 87
} // library marker zwaveTools.sendReceiveTools, line 88

////////////////////////////////////////////////////////////////////// // library marker zwaveTools.sendReceiveTools, line 90
//////        Handle Supervision request and reports           /////// // library marker zwaveTools.sendReceiveTools, line 91
//////////////////////////////////////////////////////////////////////  // library marker zwaveTools.sendReceiveTools, line 92

// @Field static results in variable being shared among all devices that use the same driver, so I use a concurrentHashMap keyed by a device's deviceNetworkId to get a unqiue value for a particular device // library marker zwaveTools.sendReceiveTools, line 94
// supervisionSessionIDs stores the last used sessionID value (0..63) for a device. It must be incremented mod 64 on each send // library marker zwaveTools.sendReceiveTools, line 95
// supervisionSentCommands stores the last command sent // library marker zwaveTools.sendReceiveTools, line 96
// Each is initialized for 64 devices, but can automatically grow // library marker zwaveTools.sendReceiveTools, line 97
@Field static ConcurrentHashMap<String, Integer> supervisionSessionIDs = new ConcurrentHashMap<String, Integer>(64, 0.75, 1) // library marker zwaveTools.sendReceiveTools, line 98
@Field static ConcurrentHashMap<String, ConcurrentHashMap> supervisionSentCommands = new ConcurrentHashMap<String, ConcurrentHashMap<Integer, String>>(64, 0.75, 1) // library marker zwaveTools.sendReceiveTools, line 99

Integer getNewSessionId() { // library marker zwaveTools.sendReceiveTools, line 101
		// Get the next session ID mod 64, but if there is no stored session ID, initialize it with a random value. // library marker zwaveTools.sendReceiveTools, line 102
		Integer lastSessionID = supervisionSessionIDs.get(device.getDeviceNetworkId() ,((Integer)(Math.random() * 64) % 64)  ) // library marker zwaveTools.sendReceiveTools, line 103
		Integer nextSessionID = (lastSessionID + 1) % 64 // increment and then mod with 64, and then store it back in the Hash table. // library marker zwaveTools.sendReceiveTools, line 104
		supervisionSessionIDs.replace(device.getDeviceNetworkId(), nextSessionID) // library marker zwaveTools.sendReceiveTools, line 105
		return nextSessionID // library marker zwaveTools.sendReceiveTools, line 106
}  // library marker zwaveTools.sendReceiveTools, line 107

Boolean getNeverSupervise() // library marker zwaveTools.sendReceiveTools, line 109
{ // library marker zwaveTools.sendReceiveTools, line 110
	Integer mfr = 			getDataValue("manufacturer")?.toInteger() // library marker zwaveTools.sendReceiveTools, line 111
	Integer deviceId = 		getDataValue("deviceId")?.toInteger() // library marker zwaveTools.sendReceiveTools, line 112
	Integer deviceType =	getDataValue("deviceType")?.toInteger() // library marker zwaveTools.sendReceiveTools, line 113
	List<Map> supervisionBroken = [ // library marker zwaveTools.sendReceiveTools, line 114
			[	manufacturer:798,  	deviceType:14,	deviceId: 1  	], // Inovelli LZW36 firmware 1.36 supervision is broken! // library marker zwaveTools.sendReceiveTools, line 115
			]  // library marker zwaveTools.sendReceiveTools, line 116
	if (userSupervisionBrokenMap) {supervisionBroken += userSupervisionBrokenMap} // library marker zwaveTools.sendReceiveTools, line 117

	Map thisDevice =	supervisionBroken.find{ ((it.manufacturer == mfr ) && (it.deviceId == deviceId ) && (it.deviceType == deviceType)) } // library marker zwaveTools.sendReceiveTools, line 119

	if (thisDevice && logEnable) log.warn "Device ${device.displayName}: This device is on the Never Supervise list. Check manufacturer for a firmware update. Not supervising." // library marker zwaveTools.sendReceiveTools, line 121
	return ( thisDevice ? true : false ) // library marker zwaveTools.sendReceiveTools, line 122
} // library marker zwaveTools.sendReceiveTools, line 123

Boolean ignoreSupervisionNoSupportCode() // library marker zwaveTools.sendReceiveTools, line 125
{ // library marker zwaveTools.sendReceiveTools, line 126
	// Some devices implement the Supervision command class incorrectly and return a "No Support" code even when they work. // library marker zwaveTools.sendReceiveTools, line 127
	// This function is to ignore the No Support code from those devices. // library marker zwaveTools.sendReceiveTools, line 128
	Integer mfr = 			getDataValue("manufacturer")?.toInteger() // library marker zwaveTools.sendReceiveTools, line 129
	Integer deviceId = 		getDataValue("deviceId")?.toInteger() // library marker zwaveTools.sendReceiveTools, line 130
	Integer deviceType =	getDataValue("deviceType")?.toInteger() // library marker zwaveTools.sendReceiveTools, line 131
	List<Map> poorSupervisionSupport = [ // library marker zwaveTools.sendReceiveTools, line 132
			[	manufacturer:12,  	deviceType:17479,	deviceId: 12340  	], // HomeSeer WD100 S2 is buggy! // library marker zwaveTools.sendReceiveTools, line 133
			[	manufacturer:12,  	deviceType:17479,	deviceId: 12342  	], // HomeSeer WD200 is buggy! // library marker zwaveTools.sendReceiveTools, line 134
			] // library marker zwaveTools.sendReceiveTools, line 135

	if (userPoorSupervisionSupportMap) {poorSupervisionSupport += userPoorSupervisionSupportMap} // library marker zwaveTools.sendReceiveTools, line 137

    Map thisDevice =	poorSupervisionSupport.find{((it.manufacturer == mfr ) && (it.deviceId == deviceId ) && (it.deviceType == deviceType)) } // library marker zwaveTools.sendReceiveTools, line 139
	if (thisDevice && logEnable) log.warn "Device ${device.displayName}: This device is on the Poor Supervise Support list. Check manufacturer for a firmware update. Ignoring 'No Support' return codes." // library marker zwaveTools.sendReceiveTools, line 140

		return ( thisDevice ? true : false )				 // library marker zwaveTools.sendReceiveTools, line 142
} // library marker zwaveTools.sendReceiveTools, line 143

Boolean getSuperviseThis() { // library marker zwaveTools.sendReceiveTools, line 145
		if (neverSupervise) return false // library marker zwaveTools.sendReceiveTools, line 146
		return (getDataValue("S2")?.toInteger() != null ) // library marker zwaveTools.sendReceiveTools, line 147
} // library marker zwaveTools.sendReceiveTools, line 148

void advancedZwaveSend(Map inputs = [:]) {  // library marker zwaveTools.sendReceiveTools, line 150
	Map params = [ cmd: null , onSuccess: null , onFailure: null , delay: null , ep: null ] // library marker zwaveTools.sendReceiveTools, line 151
	params << inputs // library marker zwaveTools.sendReceiveTools, line 152
	List<String> superviseThese = [ "2501", // Switch Binary // library marker zwaveTools.sendReceiveTools, line 153
									"2601", "2604", "2605", //  Switch MultiLevel  // library marker zwaveTools.sendReceiveTools, line 154
									"7601", // Lock V1 // library marker zwaveTools.sendReceiveTools, line 155
									"3305", // Switch Color Set									 // library marker zwaveTools.sendReceiveTools, line 156
									] // library marker zwaveTools.sendReceiveTools, line 157
	if (userDefinedSupervisionList) superviseThese += userDefinedSupervisionList								 // library marker zwaveTools.sendReceiveTools, line 158
	if (superviseThese.contains(params.cmd.CMD)) { // library marker zwaveTools.sendReceiveTools, line 159
		sendSupervised(params) // library marker zwaveTools.sendReceiveTools, line 160
	} else { // library marker zwaveTools.sendReceiveTools, line 161
		sendUnsupervised(params) // library marker zwaveTools.sendReceiveTools, line 162
	} // library marker zwaveTools.sendReceiveTools, line 163
} // library marker zwaveTools.sendReceiveTools, line 164
void advancedZwaveSend(hubitat.zwave.Command cmd, Integer ep = null ) {  // library marker zwaveTools.sendReceiveTools, line 165
	advancedZwaveSend(cmd:cmd, ep:ep) // library marker zwaveTools.sendReceiveTools, line 166
} // library marker zwaveTools.sendReceiveTools, line 167

void sendSupervised(hubitat.zwave.Command cmd, Integer ep = null ) {  // library marker zwaveTools.sendReceiveTools, line 169
    sendSupervised(cmd:cmd, ep:ep) // library marker zwaveTools.sendReceiveTools, line 170
} // library marker zwaveTools.sendReceiveTools, line 171

void sendSupervised(Map inputs = [:]) {  // library marker zwaveTools.sendReceiveTools, line 173
	Map params = [ cmd: null , onSuccess: null , onFailure: null , delay: null , ep: null ] // library marker zwaveTools.sendReceiveTools, line 174
	params << inputs // library marker zwaveTools.sendReceiveTools, line 175
	if (!(params.cmd instanceof hubitat.zwave.Command )) {  // library marker zwaveTools.sendReceiveTools, line 176
		log.error "SendSupervised called with improper command type" // library marker zwaveTools.sendReceiveTools, line 177
		return  // library marker zwaveTools.sendReceiveTools, line 178
		} // library marker zwaveTools.sendReceiveTools, line 179
	if (params.onSuccess && !(params.onSuccess instanceof hubitat.zwave.Command )) {  // library marker zwaveTools.sendReceiveTools, line 180
		log.error "SendSupervised called with improper onSuccess command type" // library marker zwaveTools.sendReceiveTools, line 181
		return  // library marker zwaveTools.sendReceiveTools, line 182
		} // library marker zwaveTools.sendReceiveTools, line 183
	if (params.onFailure && !(params.onFailure instanceof hubitat.zwave.Command )) {  // library marker zwaveTools.sendReceiveTools, line 184
		log.error "SendSupervised called with improper onFailure command type" // library marker zwaveTools.sendReceiveTools, line 185
		return  // library marker zwaveTools.sendReceiveTools, line 186
		} // library marker zwaveTools.sendReceiveTools, line 187

	if (superviseThis) { // library marker zwaveTools.sendReceiveTools, line 189
		Integer thisSessionId = getNewSessionId() // library marker zwaveTools.sendReceiveTools, line 190
		ConcurrentHashMap commandStorage = supervisionSentCommands.get(device.getDeviceNetworkId() , new ConcurrentHashMap<Integer, hubitat.zwave.Command>(64, 0.75, 1)) // library marker zwaveTools.sendReceiveTools, line 191

		hubitat.zwave.Command  supervisedCommand = zwave.supervisionV1.supervisionGet(sessionID: thisSessionId, statusUpdates: true ).encapsulate(params.cmd) // library marker zwaveTools.sendReceiveTools, line 193

		commandStorage.put(thisSessionId, [cmd:(params.cmd), onSuccess: (params.onSuccess), onFailure:(params.onFailure), ep:(params.ep), attempt:1, sessionId:thisSessionId])  // library marker zwaveTools.sendReceiveTools, line 195

		basicZwaveSend(supervisedCommand, ep)	 // library marker zwaveTools.sendReceiveTools, line 197

		Integer retryTime	=  Math.min( Math.max( (s2RetryPeriod ?: 2000), 500), 5000) // library marker zwaveTools.sendReceiveTools, line 199
		runInMillis( retryTime, supervisionCheck)	 // library marker zwaveTools.sendReceiveTools, line 200

	} else { // library marker zwaveTools.sendReceiveTools, line 202
		basicZwaveSend(params.cmd, params.ep) // library marker zwaveTools.sendReceiveTools, line 203
	} // library marker zwaveTools.sendReceiveTools, line 204
} // library marker zwaveTools.sendReceiveTools, line 205
void sendUnsupervised(hubitat.zwave.Command cmd, Integer ep = null ) {  // library marker zwaveTools.sendReceiveTools, line 206
	basicZwaveSend(cmd, ep) // library marker zwaveTools.sendReceiveTools, line 207
} // library marker zwaveTools.sendReceiveTools, line 208
void sendUnsupervised(Map inputs = [:]) {  // library marker zwaveTools.sendReceiveTools, line 209
	Map params = [ cmd: null , onSuccess: null , onFailure: null , delay: null , ep: null ] // library marker zwaveTools.sendReceiveTools, line 210
	params << inputs // library marker zwaveTools.sendReceiveTools, line 211
	basicZwaveSend(params.cmd, params.ep) // library marker zwaveTools.sendReceiveTools, line 212
} // library marker zwaveTools.sendReceiveTools, line 213

// This handles a supervised message (a "get") received from the Z-Wave device // // library marker zwaveTools.sendReceiveTools, line 215
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, Integer ep = null ) { // library marker zwaveTools.sendReceiveTools, line 216
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand((userParseMap ?: defaultParseMap)) // library marker zwaveTools.sendReceiveTools, line 217

    if (encapsulatedCommand) { // library marker zwaveTools.sendReceiveTools, line 219
		if ( ep ) { // library marker zwaveTools.sendReceiveTools, line 220
			zwaveEvent(encapsulatedCommand, ep) // library marker zwaveTools.sendReceiveTools, line 221
		} else { // library marker zwaveTools.sendReceiveTools, line 222
			zwaveEvent(encapsulatedCommand) // library marker zwaveTools.sendReceiveTools, line 223
		} // library marker zwaveTools.sendReceiveTools, line 224
    } // library marker zwaveTools.sendReceiveTools, line 225

	basicZwaveSend( new hubitat.zwave.commands.supervisionv1.SupervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0), ep) // library marker zwaveTools.sendReceiveTools, line 227
} // library marker zwaveTools.sendReceiveTools, line 228

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionReport cmd, Integer ep = null )  // library marker zwaveTools.sendReceiveTools, line 230
{ // library marker zwaveTools.sendReceiveTools, line 231
	ConcurrentHashMap whatThisDeviceSent = supervisionSentCommands?.get(device.getDeviceNetworkId() ) // library marker zwaveTools.sendReceiveTools, line 232

	Map whatWasSent = whatThisDeviceSent.get((Integer) cmd.sessionID) // library marker zwaveTools.sendReceiveTools, line 234

    if (!whatWasSent) { // library marker zwaveTools.sendReceiveTools, line 236
        log.warn "Device ${device.displayName}: Received SuperVision Report ${cmd} for endpoint ${ep},  but what was sent is null. May have been processed by a duplicate SuperVision Report. If this happens repeatedly, report issue at https://github.com/jvmahon/HubitatDriverTools/issues" // library marker zwaveTools.sendReceiveTools, line 237
        return // library marker zwaveTools.sendReceiveTools, line 238
    } // library marker zwaveTools.sendReceiveTools, line 239

	switch ((Integer) cmd.status) // library marker zwaveTools.sendReceiveTools, line 241
	{ // library marker zwaveTools.sendReceiveTools, line 242
		case 0x00: // "No Support"  // library marker zwaveTools.sendReceiveTools, line 243
			whatWasSent = whatThisDeviceSent?.remove((Integer)cmd.sessionID) // library marker zwaveTools.sendReceiveTools, line 244
			if (ignoreSupervisionNoSupportCode()) { // library marker zwaveTools.sendReceiveTools, line 245
                if (logEnable) log.warn "Device ${device.displayName}: Received a 'No Support' supervision report ${cmd} for command ${whatWasSent}, but this device has known problems with its Supervision implementation so the 'No Support' code was ignored." // library marker zwaveTools.sendReceiveTools, line 246
				if (whatWasSent.onSuccess) basicZwaveSend(whatWasSent.onSuccess, whatWasSent.ep)  // library marker zwaveTools.sendReceiveTools, line 247

			} else 	{ // library marker zwaveTools.sendReceiveTools, line 249
				log.warn "Device ${device.displayName}: Z-Wave Command supervision reported as 'No Support' for command ${whatWasSent}. If you see this warning repeatedly, please report as an issue at https://github.com/jvmahon/HubitatDriverTools/issues. Please provide the manufacturer, deviceType, and deviceId code for your device as shown on the device's Hubitat device web page." // library marker zwaveTools.sendReceiveTools, line 250
				if (whatWasSent.onFailure) basicZwaveSend(whatWasSent.onFailure, whatWasSent.ep)  // library marker zwaveTools.sendReceiveTools, line 251
			} // library marker zwaveTools.sendReceiveTools, line 252
			break // library marker zwaveTools.sendReceiveTools, line 253
		case 0x01: // "working" - Remove check if you get back a "working" status since you know device is processing the command. // library marker zwaveTools.sendReceiveTools, line 254
			whatWasSent = whatThisDeviceSent?.get((Integer)cmd.sessionID) // library marker zwaveTools.sendReceiveTools, line 255
			if (txtEnable) log.info "Device ${device.displayName}: Still processing command: ${whatWasSent}." // library marker zwaveTools.sendReceiveTools, line 256
			runInMillis(5000, supervisionCheck)	 // library marker zwaveTools.sendReceiveTools, line 257
			break ; // library marker zwaveTools.sendReceiveTools, line 258
		case 0x02: // "Fail" // library marker zwaveTools.sendReceiveTools, line 259
			whatWasSent = whatThisDeviceSent?.remove((Integer) cmd.sessionID) // library marker zwaveTools.sendReceiveTools, line 260
			log.warn "Device ${device.displayName}: Z-Wave supervised command reported failure. Failed command: ${whatWasSent}." // library marker zwaveTools.sendReceiveTools, line 261
			if (whatWasSent.onFailure) basicZwaveSend(whatWasSent.onFailure, whatWasSent.ep)  // library marker zwaveTools.sendReceiveTools, line 262

			break // library marker zwaveTools.sendReceiveTools, line 264
		case 0xFF: // "Success" // library marker zwaveTools.sendReceiveTools, line 265
			whatWasSent = whatThisDeviceSent?.remove((Integer) cmd.sessionID) // library marker zwaveTools.sendReceiveTools, line 266
			if (txtEnable || logEnable) log.info "Device ${device.displayName}: Device successfully processed supervised command ${whatWasSent}." // library marker zwaveTools.sendReceiveTools, line 267
			if (whatWasSent.onSuccess) basicZwaveSend(whatWasSent.onSuccess, whatWasSent.ep)  // library marker zwaveTools.sendReceiveTools, line 268
			break // library marker zwaveTools.sendReceiveTools, line 269
	} // library marker zwaveTools.sendReceiveTools, line 270
	if (whatThisDeviceSent?.size() < 1) unschedule(supervisionCheck) // library marker zwaveTools.sendReceiveTools, line 271
} // library marker zwaveTools.sendReceiveTools, line 272

void supervisionCheck() { // library marker zwaveTools.sendReceiveTools, line 274
    // re-attempt supervison once, else send without supervision // library marker zwaveTools.sendReceiveTools, line 275
	ConcurrentHashMap tryAgain = supervisionSentCommands?.get(device.getDeviceNetworkId()) // library marker zwaveTools.sendReceiveTools, line 276
	tryAgain?.each{ thisSessionId, whatWasSent -> // library marker zwaveTools.sendReceiveTools, line 277

		Integer retries = Math.min( Math.max( (s2MaxRetries ?: 2), 1), 5) // library marker zwaveTools.sendReceiveTools, line 279
		if (whatWasSent.attempt <  retries ) { // library marker zwaveTools.sendReceiveTools, line 280
			whatWasSent.attempt += 1 // library marker zwaveTools.sendReceiveTools, line 281
			hubitat.zwave.Command  supervisedCommand = zwave.supervisionV1.supervisionGet(sessionID: thisSessionId, statusUpdates: true ).encapsulate(whatWasSent.cmd) // library marker zwaveTools.sendReceiveTools, line 282
			if (logEnable) log.debug "Device ${device.displayName}: Reattempting command ${whatWasSent}." // library marker zwaveTools.sendReceiveTools, line 283

			basicZwaveSend(supervisedCommand, whatWasSent.ep)	 // library marker zwaveTools.sendReceiveTools, line 285
		} else { // library marker zwaveTools.sendReceiveTools, line 286
			if (logEnable) log.debug "Device ${device.displayName}: Supervision command retries exceeded. Resending command without supervision. Command ${whatWasSent}." // library marker zwaveTools.sendReceiveTools, line 287
			basicZwaveSend(whatWasSent.cmd, whatWasSent.ep) // library marker zwaveTools.sendReceiveTools, line 288
			supervisionSentCommands?.get(device.getDeviceNetworkId()).remove((Integer) thisSessionId)		 // library marker zwaveTools.sendReceiveTools, line 289
		} // library marker zwaveTools.sendReceiveTools, line 290
	} // library marker zwaveTools.sendReceiveTools, line 291
} // library marker zwaveTools.sendReceiveTools, line 292




// ~~~~~ end include (35) zwaveTools.sendReceiveTools ~~~~~

// ~~~~~ start include (31) zwaveTools.globalDataTools ~~~~~
library ( // library marker zwaveTools.globalDataTools, line 1
        base: "driver", // library marker zwaveTools.globalDataTools, line 2
        author: "jvm33", // library marker zwaveTools.globalDataTools, line 3
        category: "zwave", // library marker zwaveTools.globalDataTools, line 4
        description: "A set of tools to set up and manage data stored in a global field.", // library marker zwaveTools.globalDataTools, line 5
        name: "globalDataTools", // library marker zwaveTools.globalDataTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.globalDataTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.globalDataTools, line 8
		version: "0.0.1", // library marker zwaveTools.globalDataTools, line 9
		dependencies: "none", // library marker zwaveTools.globalDataTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/globalDataTools.groovy" // library marker zwaveTools.globalDataTools, line 11
) // library marker zwaveTools.globalDataTools, line 12

import java.util.concurrent.*  // library marker zwaveTools.globalDataTools, line 14
import groovy.transform.Field // library marker zwaveTools.globalDataTools, line 15

@Field static ConcurrentHashMap globalDataStorage = new ConcurrentHashMap(64, 0.75, 1) // library marker zwaveTools.globalDataTools, line 17

@Field static Integer dataRecordFormatVersion = 1 // library marker zwaveTools.globalDataTools, line 19

ConcurrentHashMap getDataRecordByProductType() // library marker zwaveTools.globalDataTools, line 21
{ // library marker zwaveTools.globalDataTools, line 22
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2) // library marker zwaveTools.globalDataTools, line 23
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2) // library marker zwaveTools.globalDataTools, line 24
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2) // library marker zwaveTools.globalDataTools, line 25
	String productKey = "${manufacturer}:${deviceType}:${deviceID}" // library marker zwaveTools.globalDataTools, line 26
	ConcurrentHashMap dataRecord = globalDataStorage.get(productKey, new ConcurrentHashMap<String,ConcurrentHashMap>(8, 0.75, 1)) // library marker zwaveTools.globalDataTools, line 27
	return dataRecord // library marker zwaveTools.globalDataTools, line 28
} // library marker zwaveTools.globalDataTools, line 29

ConcurrentHashMap getDataRecordByNetworkId() // library marker zwaveTools.globalDataTools, line 31
{ // library marker zwaveTools.globalDataTools, line 32
	return globalDataStorage.get(deviceNetworkId, new ConcurrentHashMap<String,ConcurrentHashMap>()) // library marker zwaveTools.globalDataTools, line 33
} // library marker zwaveTools.globalDataTools, line 34

// Debugging Functions // library marker zwaveTools.globalDataTools, line 36
void showGlobalDataRecordByProductType() { // library marker zwaveTools.globalDataTools, line 37
	// Debugging function - shows the entire concurrent @Field 'global' data record for all devices using a particular driver // library marker zwaveTools.globalDataTools, line 38

	log.debug "Data record in global storage is ${dataRecordByProductType.inspect()}." // library marker zwaveTools.globalDataTools, line 40
} // library marker zwaveTools.globalDataTools, line 41

void showFullGlobalDataRecord() { // library marker zwaveTools.globalDataTools, line 43
	// Debugging function - shows the entire concurrent @Field 'global' data record for all devices using a particular driver // library marker zwaveTools.globalDataTools, line 44
	log.debug "Global Data Record Is ${globalDataStorage.inspect()}." // library marker zwaveTools.globalDataTools, line 45
} // library marker zwaveTools.globalDataTools, line 46

// ~~~~~ end include (31) zwaveTools.globalDataTools ~~~~~

// ~~~~~ start include (32) zwaveTools.endpointTools ~~~~~
library ( // library marker zwaveTools.endpointTools, line 1
        base: "driver", // library marker zwaveTools.endpointTools, line 2
        author: "jvm33", // library marker zwaveTools.endpointTools, line 3
        category: "zwave", // library marker zwaveTools.endpointTools, line 4
        description: "Tools to Manage an Endpoint Data Record Functions", // library marker zwaveTools.endpointTools, line 5
        name: "endpointTools", // library marker zwaveTools.endpointTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.endpointTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.endpointTools, line 8
		version: "0.0.1", // library marker zwaveTools.endpointTools, line 9
		dependencies: "zwaveTools.globalDataTools", // library marker zwaveTools.endpointTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/endpointTools.groovy" // library marker zwaveTools.endpointTools, line 11
) // library marker zwaveTools.endpointTools, line 12
/* // library marker zwaveTools.endpointTools, line 13

		classVersions:[44:1, 89:1, 37:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 50:3, 94:1, 32:1, 43:1],  // library marker zwaveTools.endpointTools, line 15

		endpoints:[ // library marker zwaveTools.endpointTools, line 17
				0:[ children:[[type:'Generic Component Motion Sensor', 'namespace':'hubitat', childName:"Motion Sensor"]], // library marker zwaveTools.endpointTools, line 18
					classes:[80, 85, 89, 90, 94, 108, 112, 113, 114, 115, 122, 128, 133, 134, 135, 142, 159],  // library marker zwaveTools.endpointTools, line 19
					notificationsSupported:[7:[3, 8], 8:[1, 5], 9:[4, 5] ], // library marker zwaveTools.endpointTools, line 20
					metersSupported:[0, 2, 4, 5],  // library marker zwaveTools.endpointTools, line 21
				], // library marker zwaveTools.endpointTools, line 22

*/ // library marker zwaveTools.endpointTools, line 24

/* // library marker zwaveTools.endpointTools, line 26
Map getDeviceRecord() { // library marker zwaveTools.endpointTools, line 27
	dataRecordByProductType.get("deviceRecord", new ConcurrentHashMap(8, 0.75, 1)) // from globalDataTools // library marker zwaveTools.endpointTools, line 28
} // library marker zwaveTools.endpointTools, line 29
*/ // library marker zwaveTools.endpointTools, line 30

Map getFullEndpointRecord() { // library marker zwaveTools.endpointTools, line 32
	dataRecordByProductType.get("endpoints", new ConcurrentHashMap(8, 0.75, 1)) // from globalDataTools // library marker zwaveTools.endpointTools, line 33
} // library marker zwaveTools.endpointTools, line 34

Map getThisEndpointData(Integer ep) { // library marker zwaveTools.endpointTools, line 36
	fullEndpointRecord.get((ep ?: 0), [:]) // library marker zwaveTools.endpointTools, line 37
} // library marker zwaveTools.endpointTools, line 38

List<Integer> getThisEndpointClasses(ep) { // library marker zwaveTools.endpointTools, line 40
	List<Integer> rValue = getThisEndpointData(ep).get("classes", []) // library marker zwaveTools.endpointTools, line 41

	// If they don't exist and its endpoint 0, create them from the inClusters and secureInClusters data. // library marker zwaveTools.endpointTools, line 43
	if ( ((Integer)(ep ?: 0) == 0) && (rValue.size() == 0 ) ) { // library marker zwaveTools.endpointTools, line 44
			rValue = (getDataValue("inClusters")?.split(",").collect{ (Integer) hexStrToUnsignedInt(it) }) + ( getDataValue("secureInClusters")?.split(",").collect{ (Integer) hexStrToUnsignedInt(it)  }) // library marker zwaveTools.endpointTools, line 45
	} // library marker zwaveTools.endpointTools, line 46
	return rValue // library marker zwaveTools.endpointTools, line 47
} // library marker zwaveTools.endpointTools, line 48

Integer getEndpointCount(){ // library marker zwaveTools.endpointTools, line 50
	fullEndpointRecord.findAll({it.key != 0}).size() ?: 0 // library marker zwaveTools.endpointTools, line 51
} // library marker zwaveTools.endpointTools, line 52

Map<Integer,List> getEndpointNotificationsSupported(ep){ // library marker zwaveTools.endpointTools, line 54
	getThisEndpointData(ep).get("notificationsSupported", [:]) // library marker zwaveTools.endpointTools, line 55
} // library marker zwaveTools.endpointTools, line 56

List<Integer> getEndpointMetersSupported( Integer ep = null ){ // library marker zwaveTools.endpointTools, line 58
	getThisEndpointData(ep).get("metersSupported", []) // library marker zwaveTools.endpointTools, line 59
} // library marker zwaveTools.endpointTools, line 60

// Get the endpoint number for a child device // library marker zwaveTools.endpointTools, line 62
Integer getChildEndpointNumber(com.hubitat.app.DeviceWrapper thisChild) { // library marker zwaveTools.endpointTools, line 63
	if (! thisChild) return 0 // library marker zwaveTools.endpointTools, line 64
	thisChild.deviceNetworkId.split("-ep")[-1] as Integer // library marker zwaveTools.endpointTools, line 65
} // library marker zwaveTools.endpointTools, line 66


// Get List (possibly multiple) child device for a specific endpoint. Child devices can also be of the form '-ep000'  // library marker zwaveTools.endpointTools, line 69
// Child devices associated with the root device end in -ep000 // library marker zwaveTools.endpointTools, line 70
List<com.hubitat.app.DeviceWrapper> getChildDeviceListByEndpoint( Integer ep ) { // library marker zwaveTools.endpointTools, line 71
	childDevices.findAll{ getChildEndpointNumber(it)  == (ep ?: 0) } // library marker zwaveTools.endpointTools, line 72
} // library marker zwaveTools.endpointTools, line 73


void sendEventToEndpoints(Map inputs) // library marker zwaveTools.endpointTools, line 76
{ // library marker zwaveTools.endpointTools, line 77
	Map params = [ event: null , ep: null , addRootToEndpoint0: true , alwaysSend: null ] // library marker zwaveTools.endpointTools, line 78

	if (! (inputs.every{ k, v ->  params.containsKey(k) } ) ) { // library marker zwaveTools.endpointTools, line 80
		log.error "Error calling sendEventToEndpoints. Supported parameters are ${params.keySet()}. Function was called with parameters: ${inputs.keySet()}" // library marker zwaveTools.endpointTools, line 81
		return // library marker zwaveTools.endpointTools, line 82
	} // library marker zwaveTools.endpointTools, line 83

	params << inputs << [ep: ((params.ep ?: 0) as Integer)] // library marker zwaveTools.endpointTools, line 85

	List<com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(params.ep) // library marker zwaveTools.endpointTools, line 87

	if ((params.ep == 0) && (params.addRootToEndpoint0))  { targetDevices += device } // library marker zwaveTools.endpointTools, line 89

	targetDevices.each { // library marker zwaveTools.endpointTools, line 91
		if (it.hasAttribute(params.event.name) || params.alwaysSend?.contains(params.event.name) ) {  // library marker zwaveTools.endpointTools, line 92
			it.sendEvent(params.event)  // library marker zwaveTools.endpointTools, line 93
		} // library marker zwaveTools.endpointTools, line 94
	} // library marker zwaveTools.endpointTools, line 95
} // library marker zwaveTools.endpointTools, line 96

void sendEventToAll(Map inputs) // library marker zwaveTools.endpointTools, line 98
{ // library marker zwaveTools.endpointTools, line 99
	Map params = [ event: null , addRootToEndpoint0: true , alwaysSend: null ] // library marker zwaveTools.endpointTools, line 100

	if (! (inputs.every{ k, v ->  params.containsKey(k) } ) ) { // library marker zwaveTools.endpointTools, line 102
		log.error "Error calling sendEventToEndpoints. Supported parameters are ${params.keySet()}. Function was called with parameters: ${inputs.keySet()}" // library marker zwaveTools.endpointTools, line 103
		return // library marker zwaveTools.endpointTools, line 104
	} // library marker zwaveTools.endpointTools, line 105
	params << inputs // library marker zwaveTools.endpointTools, line 106
	(childDevices + device).each { // library marker zwaveTools.endpointTools, line 107
		if (it.hasAttribute(params.event.name) || params.alwaysSend?.contains(params.event.name) ) {  // library marker zwaveTools.endpointTools, line 108
			it.sendEvent(params.event)  // library marker zwaveTools.endpointTools, line 109
		} // library marker zwaveTools.endpointTools, line 110
	} // library marker zwaveTools.endpointTools, line 111
} // library marker zwaveTools.endpointTools, line 112


// Debugging Functions // library marker zwaveTools.endpointTools, line 115
void showEndpointlDataRecord() { // library marker zwaveTools.endpointTools, line 116
	// Debugging function - shows the entire concurrent @Field 'global' data record for all devices using a particular driver // library marker zwaveTools.endpointTools, line 117
	log.debug "Endpoint Data record is ${fullEndpointRecord.inspect()}." // library marker zwaveTools.endpointTools, line 118
} // library marker zwaveTools.endpointTools, line 119

// ~~~~~ end include (32) zwaveTools.endpointTools ~~~~~

// ~~~~~ start include (30) zwaveTools.batteryTools ~~~~~
library ( // library marker zwaveTools.batteryTools, line 1
        base: "driver", // library marker zwaveTools.batteryTools, line 2
        author: "jvm33", // library marker zwaveTools.batteryTools, line 3
        category: "zwave", // library marker zwaveTools.batteryTools, line 4
        description: "Handle Interactions with the Hubitat Hub", // library marker zwaveTools.batteryTools, line 5
        name: "batteryTools", // library marker zwaveTools.batteryTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.batteryTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.batteryTools, line 8
        version:"0.0.1", // library marker zwaveTools.batteryTools, line 9
		dependencies: "none", // library marker zwaveTools.batteryTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/batteryTools.groovy" // library marker zwaveTools.batteryTools, line 11

) // library marker zwaveTools.batteryTools, line 13

void zwaveEvent(hubitat.zwave.commands.batteryv3.BatteryReport cmd)  {  // library marker zwaveTools.batteryTools, line 15
	if (cmd.batteryLevel == 0xFF) { // library marker zwaveTools.batteryTools, line 16
		batteryEvent = [name: "battery", value:1, unit: "%", descriptionText: "Low Battery Alert 1%. Change now!"] // library marker zwaveTools.batteryTools, line 17
	} else { // library marker zwaveTools.batteryTools, line 18
		batteryEvent = [name: "battery", value:cmd.batteryLevel, unit: "%", descriptionText: "Battery level ${cmd.batteryLevel}%."] // library marker zwaveTools.batteryTools, line 19
	} // library marker zwaveTools.batteryTools, line 20
	(childDevices + device).each{ it.sendEvent(batteryEvent) } // library marker zwaveTools.batteryTools, line 21
} // library marker zwaveTools.batteryTools, line 22

void batteryTools_refreshBattery() { // library marker zwaveTools.batteryTools, line 24
	advancedZwaveSend(zwave.batteryV1.batteryGet())  // library marker zwaveTools.batteryTools, line 25
} // library marker zwaveTools.batteryTools, line 26

// ~~~~~ end include (30) zwaveTools.batteryTools ~~~~~

// ~~~~~ start include (34) zwaveTools.zwaveDeviceDatabase ~~~~~
import groovy.transform.Field // library marker zwaveTools.zwaveDeviceDatabase, line 1

library ( // library marker zwaveTools.zwaveDeviceDatabase, line 3
        base: "driver", // library marker zwaveTools.zwaveDeviceDatabase, line 4
        author: "jvm33", // library marker zwaveTools.zwaveDeviceDatabase, line 5
        category: "zwave", // library marker zwaveTools.zwaveDeviceDatabase, line 6
        description: "Database of Device Characteristics", // library marker zwaveTools.zwaveDeviceDatabase, line 7
        name: "zwaveDeviceDatabase", // library marker zwaveTools.zwaveDeviceDatabase, line 8
        namespace: "zwaveTools", // library marker zwaveTools.zwaveDeviceDatabase, line 9
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.zwaveDeviceDatabase, line 10
		version: "0.0.1", // library marker zwaveTools.zwaveDeviceDatabase, line 11
		dependencies: "none", // library marker zwaveTools.zwaveDeviceDatabase, line 12
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/zwaveDeviceDatabase.groovy" // library marker zwaveTools.zwaveDeviceDatabase, line 13
) // library marker zwaveTools.zwaveDeviceDatabase, line 14

Map getThisDeviceDatabaseRecord() { // library marker zwaveTools.zwaveDeviceDatabase, line 16
	Integer mfr = getDataValue("manufacturer")?.toInteger() // library marker zwaveTools.zwaveDeviceDatabase, line 17
	Integer Id = getDataValue("deviceId")?.toInteger() // library marker zwaveTools.zwaveDeviceDatabase, line 18
	Integer Type = getDataValue("deviceType")?.toInteger() // library marker zwaveTools.zwaveDeviceDatabase, line 19

	return localDeviceDatabase.find{ DBentry -> // library marker zwaveTools.zwaveDeviceDatabase, line 21
					DBentry?.fingerprints.find{ subElement->  // library marker zwaveTools.zwaveDeviceDatabase, line 22
							((subElement.manufacturer == mfr) && (subElement.deviceId == Id) && (subElement.deviceType == Type ))  // library marker zwaveTools.zwaveDeviceDatabase, line 23
						} // library marker zwaveTools.zwaveDeviceDatabase, line 24
				} // library marker zwaveTools.zwaveDeviceDatabase, line 25
} // library marker zwaveTools.zwaveDeviceDatabase, line 26

@Field static List localDeviceDatabase =  // library marker zwaveTools.zwaveDeviceDatabase, line 28
[ // library marker zwaveTools.zwaveDeviceDatabase, line 29
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 30
		formatVersion:1, // library marker zwaveTools.zwaveDeviceDatabase, line 31
		fingerprints: [ // library marker zwaveTools.zwaveDeviceDatabase, line 32
				[manufacturer:838, deviceId: 769,  deviceType:769, name:"Ring G2 Motion Sensor"] // library marker zwaveTools.zwaveDeviceDatabase, line 33
				], // library marker zwaveTools.zwaveDeviceDatabase, line 34
		classVersions:[0:1, 85:0, 89:1, 90:1, 94:1, 108:0, 112:1, 113:8, 114:1, 115:1, 122:1, 128:1, 133:2, 134:2, 135:3, 142:3, 159:0],  // library marker zwaveTools.zwaveDeviceDatabase, line 35
		endpoints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 36
				0:[ children:[[type:'Generic Component Motion Sensor', 'namespace':'hubitat', childName:"Motion Sensor"]], // library marker zwaveTools.zwaveDeviceDatabase, line 37
					classes:[80, 85, 89, 90, 94, 108, 112, 113, 114, 115, 122, 128, 133, 134, 135, 142, 159],  // library marker zwaveTools.zwaveDeviceDatabase, line 38
					notificationsSupported:[7:[3, 8], 8:[1, 5], 9:[4, 5]]] // library marker zwaveTools.zwaveDeviceDatabase, line 39
				], // library marker zwaveTools.zwaveDeviceDatabase, line 40
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 41
			1:[ size:1,	category:"advanced", name:"1", title:"(1) Heartbeat Interval", description:"Number of minutes between heartbeats.", range:"1..70",  type:"number" ], // library marker zwaveTools.zwaveDeviceDatabase, line 42
			2:[ size:1,	category:"advanced", name:"2", title:"(2) Application Retries", description:"Number of application level retries attempted", range:"0..5", type:"number" ], // library marker zwaveTools.zwaveDeviceDatabase, line 43
			3:[ size:1,	category:"advanced", name:"3", title:"(3) App Level Retry Base Wait", description:"Application Level Retry Base Wait Time Period (seconds)", range:"1..96", type:"number"], // library marker zwaveTools.zwaveDeviceDatabase, line 44
			4:[ size:1,	category:"advanced", name:"4", title:"(4) LED Indicator Enable", description:"Configure the various LED indications on the device", options:[0:"Don’t show green", 1:"Show green after Supervision Report Intrusion (Fault)", 2:"Show green after Supervision Report both Intrusion and Intrusion clear"], type:"enum" ], // library marker zwaveTools.zwaveDeviceDatabase, line 45
			5:[ size:1,	category:"advanced", name:"5", title:"(5) Occupancy Signal Clear", range:"0..255", type:"number"], // library marker zwaveTools.zwaveDeviceDatabase, line 46
			6:[ size:1,	category:"advanced", name:"6", title:"(6) Intrusion Clear Delay", range:"0..255", type:"number"], // library marker zwaveTools.zwaveDeviceDatabase, line 47
			7:[ size:1,	category:"advanced", name:"7", title:"(7) Standard Delay Time", range:"0..255", type:"number"], // library marker zwaveTools.zwaveDeviceDatabase, line 48
			8:[ size:1,	category:"basic", name:"8", title:"(8) Motion Detection Mode", description:"Adjusts motion sensitivity, 0 = low ... 4 = high", range:"0..4", type:"number" ], // library marker zwaveTools.zwaveDeviceDatabase, line 49
			9:[ size:1,	category:"advanced", name:"9", title:"(9) Lighting Enabled", range:"0..1", type:"number"], // library marker zwaveTools.zwaveDeviceDatabase, line 50
			10:[size:1, category:"advanced", name:"10", title:"(10) Lighting Delay", description:"Delay to turn off lights when motion no longer detected", range:"0..60", type:"number"], // library marker zwaveTools.zwaveDeviceDatabase, line 51
			11:[size:2, category:"advanced", name:"11", title:"(11) Supervisory Report Timeout", range:"500..30000", type:"number"] // library marker zwaveTools.zwaveDeviceDatabase, line 52
		] // library marker zwaveTools.zwaveDeviceDatabase, line 53
	], // library marker zwaveTools.zwaveDeviceDatabase, line 54
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 55
	formatVersion:1,  // library marker zwaveTools.zwaveDeviceDatabase, line 56
	fingerprints:[['manufacturer':634, 'deviceId':18, 'deviceType':769, name:'Zooz: ZSE18']],  // library marker zwaveTools.zwaveDeviceDatabase, line 57
	classVersions:[89:1, 48:2, 152:0, 0:1, 132:2, 122:1, 133:2, 112:1, 134:2, 113:5, 114:1, 115:1, 159:0, 90:1, 128:1, 108:0, 94:1, 85:0, 32:1],  // library marker zwaveTools.zwaveDeviceDatabase, line 58
	endpoints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 59
		0:[	children:[[type:'Generic Component Motion Sensor', 'namespace':'hubitat', childName:"Motion Sensor"]], // library marker zwaveTools.zwaveDeviceDatabase, line 60
			classes:[0, 32, 48, 85, 89, 90, 94, 108, 112, 113, 114, 115, 122, 128, 132, 133, 134, 152, 159],  // library marker zwaveTools.zwaveDeviceDatabase, line 61
			notificationsSupported:[7:[8, 9]]] // library marker zwaveTools.zwaveDeviceDatabase, line 62
		],  // library marker zwaveTools.zwaveDeviceDatabase, line 63
	deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 64
		12:[size:1, name:'12', description:' 1 = low sensitivity and 8 = high sensitivity.', range:'1..8', title:'(12)  PIR sensor sensitivity', type:'number'],  // library marker zwaveTools.zwaveDeviceDatabase, line 65
		14:[size:1, name:'14', options:[0:'Disabled', 1:'Enabled'], title:'(14) BASIC SET reports', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 66
		15:[size:1, name:'15', options:[0:'Send 255 on motion, 0 on clear (normal)', 1:'Send 0 on motion, 255 on clear (reversed)'], title:'(15) reverse BASIC SET', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 67
		17:[size:1, name:'17', options:[0:'Disabled', 1:'Enabled'], title:'(17) vibration sensor', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 68
		18:[size:2, name:'18', description:'3=6 seconds, 65535=65538 seconds (add 3 seconds to value set)', range:'3..65535', title:'(18) trigger interval', type:'number'],  // library marker zwaveTools.zwaveDeviceDatabase, line 69
		19:[size:1, name:'19', options:[ 0:'Send Notification Reports to Hub', 1:'Send Binary Sensor Reports to Hub'], title:'(19) Report Type', type:'enum'], 		 // library marker zwaveTools.zwaveDeviceDatabase, line 70
		20:[size:1, name:'20', options:[0:'Disabled', 1:'Enabled'],title:'(20) LED Indicator', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 71
		32:[size:1, name:'32', description:'percent battery left', range:'10..50', title:'(32) Low Battery Alert', type:'number']] // library marker zwaveTools.zwaveDeviceDatabase, line 72
	],	 // library marker zwaveTools.zwaveDeviceDatabase, line 73
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 74
		formatVersion:1, // library marker zwaveTools.zwaveDeviceDatabase, line 75
		fingerprints: [ // library marker zwaveTools.zwaveDeviceDatabase, line 76
				[manufacturer:0x0184, 	deviceId: 0x3034,  deviceType:0x4447, name:"Dragon Tech WD100"], // library marker zwaveTools.zwaveDeviceDatabase, line 77
				[manufacturer:0x000C, 	deviceId: 0x3034,  deviceType:0x4447, name:"HomeSeer WD100+"], // library marker zwaveTools.zwaveDeviceDatabase, line 78
				[manufacturer:0x0315, 	deviceId: 0x3034,  deviceType:0x4447, name:"ZLink Products WD100+"], // library marker zwaveTools.zwaveDeviceDatabase, line 79
				], // library marker zwaveTools.zwaveDeviceDatabase, line 80
		classVersions: [89:1, 38:1, 39:1, 122:2, 133:2, 112:1, 134:2, 114:2, 115:1, 90:1, 91:1, 94:1, 32:1, 43:1], // library marker zwaveTools.zwaveDeviceDatabase, line 81
		endpoints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 82
				0:[ children:[	[type:'Generic Component Dimmer', 'namespace':'hubitat']], // library marker zwaveTools.zwaveDeviceDatabase, line 83
					classes:[94, 134, 114, 90, 133, 89, 115, 38, 39, 112, 44, 43, 91, 122]] // library marker zwaveTools.zwaveDeviceDatabase, line 84
				], // library marker zwaveTools.zwaveDeviceDatabase, line 85
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 86
			4:[ size:1,	category:"basic", name:"4", title:"(4) Orientation", description:"Control the on/off orientation of the rocker switch", options:[0:"Normal", 1:"Inverted"], type:"enum" ], // library marker zwaveTools.zwaveDeviceDatabase, line 87

			7:[ size:2,	category:"basic", name:"7", title:"(7) Remote Dimming Level Increment", range:"1..99", type:"number"], // library marker zwaveTools.zwaveDeviceDatabase, line 89
			8:[ size:2,	category:"basic", name:"8", title:"(8) Remote Dimming Level Duration", description:"Time interval (in tens of ms) of each brightness level change when controlled locally", range:"0..255", type:"number" ], // library marker zwaveTools.zwaveDeviceDatabase, line 90
			9:[ size:2,	category:"basic", name:"9", title:"(9) Local Dimming Level Increment", range:"1..99", type:"number"], // library marker zwaveTools.zwaveDeviceDatabase, line 91
			10:[size:2, category:"basic", name:"10", title:"(10) Local Dimming Level Duration", description:"Time interval (in tens of ms) of each brightness level change when controlled locally", range:"0..255", type:"number"] // library marker zwaveTools.zwaveDeviceDatabase, line 92
		] // library marker zwaveTools.zwaveDeviceDatabase, line 93
	], // library marker zwaveTools.zwaveDeviceDatabase, line 94
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 95

		formatVersion:1,  // library marker zwaveTools.zwaveDeviceDatabase, line 97
		fingerprints: [ // library marker zwaveTools.zwaveDeviceDatabase, line 98
				[manufacturer:0x000C, 	deviceId: 0x3033,  deviceType:0x4447, name:"HomeSeer WS100 Switch"], // library marker zwaveTools.zwaveDeviceDatabase, line 99
				], // library marker zwaveTools.zwaveDeviceDatabase, line 100
		classVersions:[44:1, 89:1, 37:1, 39:1, 0:1, 122:1, 133:1, 112:1, 134:1, 114:1, 115:1, 90:1, 91:1, 94:1, 32:1, 43:1],  // library marker zwaveTools.zwaveDeviceDatabase, line 101
		endpoints:[	 // library marker zwaveTools.zwaveDeviceDatabase, line 102
					0:[children:[ [type:'Generic Component Switch', 'namespace':'hubitat']	], // library marker zwaveTools.zwaveDeviceDatabase, line 103
						classes:[0, 32, 37, 39, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]] // library marker zwaveTools.zwaveDeviceDatabase, line 104
				],  // library marker zwaveTools.zwaveDeviceDatabase, line 105
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 106
			3:[ size:1, level:"basic", name:"3", title:"(3) LED Indication Configuration", options:[0:"LED On when device is Off", 1:"LED On when device is On", 2:"LED always Off", 4:"LED always On"], type:"enum" ], // library marker zwaveTools.zwaveDeviceDatabase, line 107
			4:[ size:1, level:"advanced", name:"4", title:"(4) Orientation", description:"Controls the on/off orientation of the rocker switch", options:[0:"Normal", 1:"Inverted"], type:"enum"], // library marker zwaveTools.zwaveDeviceDatabase, line 108
		] // library marker zwaveTools.zwaveDeviceDatabase, line 109
	], // library marker zwaveTools.zwaveDeviceDatabase, line 110
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 111
		formatVersion:1,  // library marker zwaveTools.zwaveDeviceDatabase, line 112
		fingerprints:[[manufacturer:12, deviceId:12342, deviceType:17479, name:'HomeSeer Technologies: HS-WD200+']],  // library marker zwaveTools.zwaveDeviceDatabase, line 113
		classVersions:[44:1, 89:1, 38:3, 39:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1, 43:1],  // library marker zwaveTools.zwaveDeviceDatabase, line 114
		endpoints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 115
					0:[	children:[ [type:'Generic Component Dimmer', 'namespace':'hubitat']	], // library marker zwaveTools.zwaveDeviceDatabase, line 116
						classes:[0, 32, 38, 39, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]] // library marker zwaveTools.zwaveDeviceDatabase, line 117
				],  // library marker zwaveTools.zwaveDeviceDatabase, line 118
		deviceInputs:[ // Firmware 5.14 and above! // library marker zwaveTools.zwaveDeviceDatabase, line 119
			3:[size:1, name:'3', options:['0':'Bottom LED ON if load is OFF', '1':'Bottom LED OFF if load is OFF'], title:'(3) Bottom LED Operation', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 120
			4:[size:1, name:'4', options:['0':'Normal - Top of Paddle turns load ON', '1':'Inverted - Bottom of Paddle turns load ON'], title:'(4) Paddle Orientation', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 121
			5:[size:1, name:'5', options:['0':'(0) No minimum set', '1':'(1) 6.5%', '2':'(2) 8%', '3':'(3) 9%', '4':'(4) 10%', '5':'(5) 11%', '6':'(6) 12%', '7':'(7) 13%', '8':'(8) 14%', '9':'(9) 15%', '10':'(10) 16%', '11':'(11) 17%', '12':'(12) 18%', '13':'(13) 19%', '14':'(14) 20%'], title:'(5) Minimum Dimming Level', type:'enum'], // library marker zwaveTools.zwaveDeviceDatabase, line 122
 			6:[size:1, name:'6', options:['0':'Central Scene Enabled', '1':'Central Scene Disabled'], title:'(5) Central Scene Enable/Disable', type:'enum'], 			 // library marker zwaveTools.zwaveDeviceDatabase, line 123
			11:[size:1, name:'11', range:'0..90', title:'(11) Set dimmer ramp rate for remote control (seconds)', type:'number'],  // library marker zwaveTools.zwaveDeviceDatabase, line 124
			12:[size:1, name:'12', range:'0..90', title:'(12) Set dimmer ramp rate for local control (seconds)', type:'number'],  // library marker zwaveTools.zwaveDeviceDatabase, line 125
			13:[size:1, name:'13', options:['0':'LEDs show load status', '1':'LEDs display a custom status'], description:'Set dimmer display mode', title:'(13) Status Mode', type:'enum'], 	 // library marker zwaveTools.zwaveDeviceDatabase, line 126
			14:[size:1, name:'14', options:['0':'White', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan'], title:'(14) Set the LED color when displaying load status', type:'enum'], 		 // library marker zwaveTools.zwaveDeviceDatabase, line 127
			21:[size:1, name:'21', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(21) Status LED 1 Color (bottom LED)', type:'enum'], // library marker zwaveTools.zwaveDeviceDatabase, line 128
			22:[size:1, name:'22', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(22) Status LED 2 Color', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 129
			23:[size:1, name:'23', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(23) Status LED 3 Color', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 130

			24:[size:1, name:'24', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(24) Status LED 4 Color', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 132

			25:[size:1, name:'25', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(25) Status LED 5 Color', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 134
			26:[size:1, name:'26', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(26) Status LED 6 Color', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 135
			27:[size:1, name:'27', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(27) Status LED 7 Color (top LED)', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 136
			30:[size:1, name:'30', range:'0..255', title:'(30) Blink Frequency when displaying custom status', type:'number'],  // library marker zwaveTools.zwaveDeviceDatabase, line 137
			31:[size:1, name:'31', type:'number', title:'(31) LED 7 Blink Status - bitmap', description:'bitmap defines LEDs to blink '],  // library marker zwaveTools.zwaveDeviceDatabase, line 138

		] // library marker zwaveTools.zwaveDeviceDatabase, line 140
	], // library marker zwaveTools.zwaveDeviceDatabase, line 141
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 142
		formatVersion:1,  // library marker zwaveTools.zwaveDeviceDatabase, line 143
		fingerprints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 144
			[manufacturer:12, deviceId:12341, deviceType:17479, name:'HomeSeer Technologies: HS-WS200+'] // library marker zwaveTools.zwaveDeviceDatabase, line 145
			],  // library marker zwaveTools.zwaveDeviceDatabase, line 146
		classVersions:[44:1, 89:1, 37:1, 39:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1, 43:1],  // library marker zwaveTools.zwaveDeviceDatabase, line 147
		endpoints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 148
					0:[	children:[ [type:'Generic Component Switch', 'namespace':'hubitat']	], // library marker zwaveTools.zwaveDeviceDatabase, line 149
						classes:[0, 32, 37, 39, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]] // library marker zwaveTools.zwaveDeviceDatabase, line 150
				],  // library marker zwaveTools.zwaveDeviceDatabase, line 151
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 152
			3:[size:1, name:'3', options:[0:'LED ON if load is OFF', '1':'LED OFF if load is OFF'], description:'Sets LED operation (in normal mode)', title:'(3) Bottom LED Operation', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 153
			4:[size:1, name:'4', options:[0:'Top of Paddle turns load ON', '1':'Bottom of Paddle turns load ON'], description:'Sets paddle’s load orientation', title:'(4) Orientation', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 154
			6:[size:1, name:'6', options:[0:'Disabled', '1':'Enabled'], description:'Enable or Disable Scene Control', title:'(6) Scene Control', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 155
			13:[size:1, name:'13', options:[0:'Normal mode (load status)', '1':'Status mode (custom status)'], description:'Sets switch mode of operation', title:'(13) Status Mode', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 156
			14:[size:1, name:'14', options:['0':'White', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan'], description:'Sets the Normal mode LED color', title:'(14) Load Status LED Color', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 157
			21:[size:1, name:'21', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], description:'Sets the Status mode LED color', title:'(21) Status LED Color', type:'enum'], // library marker zwaveTools.zwaveDeviceDatabase, line 158
			31:[size:1, name:'31', description:'Sets the switch LED Blink frequency', range:'0..255', title:'(31) Blink Frequency', type:'number'], // library marker zwaveTools.zwaveDeviceDatabase, line 159
		] // library marker zwaveTools.zwaveDeviceDatabase, line 160
	], // library marker zwaveTools.zwaveDeviceDatabase, line 161
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 162
		formatVersion:1,  // library marker zwaveTools.zwaveDeviceDatabase, line 163
		fingerprints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 164
			[manufacturer:99, deviceId:12597, deviceType:18770, name:'Jasco Products: 46201'] // library marker zwaveTools.zwaveDeviceDatabase, line 165
			],  // library marker zwaveTools.zwaveDeviceDatabase, line 166
		classVersions:[44:1, 34:1, 89:1, 37:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1, 43:1],  // library marker zwaveTools.zwaveDeviceDatabase, line 167
		endpoints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 168
				0:[	children:[ [type:'Generic Component Switch', 'namespace':'hubitat']	], // library marker zwaveTools.zwaveDeviceDatabase, line 169
					classes:[0, 32, 34, 37, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]] // library marker zwaveTools.zwaveDeviceDatabase, line 170
			],  // library marker zwaveTools.zwaveDeviceDatabase, line 171
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 172
			3:[size:1, name:'3', range:'0..255', title:'(3) Blue LED Night Light', type:'number'] // library marker zwaveTools.zwaveDeviceDatabase, line 173
		] // library marker zwaveTools.zwaveDeviceDatabase, line 174
	], // library marker zwaveTools.zwaveDeviceDatabase, line 175
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 176
		formatVersion:1,  // library marker zwaveTools.zwaveDeviceDatabase, line 177
		fingerprints:[[manufacturer:99, deviceId:12338, deviceType:20292, name:'GE/Jasco Heavy Duty Switch 14285']],  // library marker zwaveTools.zwaveDeviceDatabase, line 178
		classVersions:[44:1, 89:1, 37:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 50:3, 94:1, 32:1, 43:1],  // library marker zwaveTools.zwaveDeviceDatabase, line 179
		endpoints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 180
				0:[	children:[ [type:'Generic Component Switch', 'namespace':'hubitat']	], // library marker zwaveTools.zwaveDeviceDatabase, line 181
					classes:[0, 32, 37, 43, 44, 50, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134],  // library marker zwaveTools.zwaveDeviceDatabase, line 182
					metersSupported:[0, 2, 4, 5, 6]] // library marker zwaveTools.zwaveDeviceDatabase, line 183
			],  // library marker zwaveTools.zwaveDeviceDatabase, line 184
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 185
			1:[size:1, name:'1', options:['0':'Return to last state', '1':'Return to off', '2':'Return to on'], title:'(1) Product State after Power Reset', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 186
			2:[size:1, name:'2', options:['0':'Once monthly', '1':'Reports based on Parameter 3 setting', '2':'Once daily'], title:'(2) Energy Report Mode', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 187
			3:[size:1, name:'3', range:'5..60', title:'(3) Energy Report Frequency', type:'number'],  // library marker zwaveTools.zwaveDeviceDatabase, line 188
			19:[size:1, name:'19', options:['0':'Default', '1':'Alternate Exclusion (3 button presses)'], title:'(19) Alternate Exclusion', type:'enum'] // library marker zwaveTools.zwaveDeviceDatabase, line 189
			] // library marker zwaveTools.zwaveDeviceDatabase, line 190
	], // library marker zwaveTools.zwaveDeviceDatabase, line 191
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 192
		formatVersion:1,  // library marker zwaveTools.zwaveDeviceDatabase, line 193
		fingerprints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 194
				[manufacturer:634, deviceId:40963, deviceType:40960, name:'Zooz: ZEN25'] // library marker zwaveTools.zwaveDeviceDatabase, line 195
			],  // library marker zwaveTools.zwaveDeviceDatabase, line 196
		classVersions:[0:1, 32:1, 37:1, 50:3, 89:1, 90:1, 94:1, 96:2, 112:1, 113:8, 114:1, 115:1, 122:1, 133:2, 134:2, 142:3],  // library marker zwaveTools.zwaveDeviceDatabase, line 197
		endpoints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 198
				0:[	children:[[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 Double Outlet"]], // library marker zwaveTools.zwaveDeviceDatabase, line 199
					classes:[0, 32, 37, 50, 89, 90, 94, 96, 112, 113, 114, 115, 122, 133, 134, 142]],  // library marker zwaveTools.zwaveDeviceDatabase, line 200
				1:[ children:[[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 Left Outlet"]],  // library marker zwaveTools.zwaveDeviceDatabase, line 201
					classes:[32, 37, 50, 89, 94, 133, 142],  // library marker zwaveTools.zwaveDeviceDatabase, line 202
					metersSupported:[0, 2, 4, 5]],  // library marker zwaveTools.zwaveDeviceDatabase, line 203
				2:[ children:[[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 Right Outlet"]],  // library marker zwaveTools.zwaveDeviceDatabase, line 204
					classes:[32, 37, 50, 89, 94, 133, 142],  // library marker zwaveTools.zwaveDeviceDatabase, line 205
					metersSupported:[0, 2, 4, 5]],  // library marker zwaveTools.zwaveDeviceDatabase, line 206
				3:[ children:[[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 USB Port"]],  // library marker zwaveTools.zwaveDeviceDatabase, line 207
					classes:[32, 37, 50, 89, 94, 133, 142]] // library marker zwaveTools.zwaveDeviceDatabase, line 208
			],  // library marker zwaveTools.zwaveDeviceDatabase, line 209
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 210
				1:[name:'1', title:'(1) On/Off After Power', size:1, description:'On/Off Status After Power Failure', type:'enum', options:[0:'Previous State', 1:'On', 2:'Off']],  // library marker zwaveTools.zwaveDeviceDatabase, line 211
				2:[name:'2', title:'(2) Wattage Threshold', size:4, description:'Power Wattage Report Value Threshold', type:'number', range:0..65535],  // library marker zwaveTools.zwaveDeviceDatabase, line 212
				3:[name:'3', title:'(3) Wattage Frequency', size:4, description:'Power Wattage Report Frequency', type:'number', range:30..2678400],  // library marker zwaveTools.zwaveDeviceDatabase, line 213
				4:[name:'4', title:'(4) Energy Frequency', size:4, description:'Energy (kWh) Report Frequency', type:'number', range:30..2678400],  // library marker zwaveTools.zwaveDeviceDatabase, line 214
				5:[name:'5', title:'(5) Voltage Frequency', size:4, description:'Voltage (V) Report Frequency', type:'number', range:30..2678400],  // library marker zwaveTools.zwaveDeviceDatabase, line 215
				6:[name:'6', title:'(6) Current Frequency', size:4, description:'Electrical Current (A) Report Frequency', type:'number', range:30..2678400],  // library marker zwaveTools.zwaveDeviceDatabase, line 216
				7:[name:'7', title:'(7) Overload Protection', size:1, type:'number', range:1..10],  // library marker zwaveTools.zwaveDeviceDatabase, line 217
				8:[name:'8', title:'(8) Enable Auto-Off (Left)', size:1, description:'Enable Auto Turn-Off Timer for Left Outlet', type:'enum', options:[0:'Disable', 1:'Enable']],  // library marker zwaveTools.zwaveDeviceDatabase, line 218
				9:[name:'9', title:'(9) Turn-Off Time, Minutes (Left)', size:4, description:'Auto Turn-Off Time for Left Outlet', type:'number', range:1..65535],  // library marker zwaveTools.zwaveDeviceDatabase, line 219
				10:[name:'10', title:'(10) Enable Auto-On (Left)', size:1, description:'Enable Auto Turn-On Timer for Left Outlet', type:'enum', options:[0:'Disable', 1:'Enable']],  // library marker zwaveTools.zwaveDeviceDatabase, line 220
				11:[name:'11', title:'(11) Turn-On Time, Minutes (Left)', size:4, description:'Auto Turn-On Time for Left Outlet', type:'number', range:1..65535],  // library marker zwaveTools.zwaveDeviceDatabase, line 221
				12:[name:'12', title:'(12) Enable Auto-Off (Right)', size:1, description:'Enable Auto Turn-Off Timer for Right Outlet', type:'enum', options:[0:'Disable', 1:'Enable']],  // library marker zwaveTools.zwaveDeviceDatabase, line 222
				13:[name:'13', title:'(13) Turn-Off Time, Minutes (Right)', size:4, description:'Auto Turn-Off Time for Right Outlet', type:'number', range:1..65535],  // library marker zwaveTools.zwaveDeviceDatabase, line 223
				14:[name:'14', title:'(14) Enable Auto-On (Right)', size:1, description:'Enable Auto Turn-On Timer for Right Outlet', type:'enum', options:[0:'Disable', 1:'Enable']],  // library marker zwaveTools.zwaveDeviceDatabase, line 224
				15:[name:'15', title:'(15) Turn-On Time, Minutes (Right)', size:4, description:'Auto Turn-On Time for Right Outlet', type:'number', range:1..65535],  // library marker zwaveTools.zwaveDeviceDatabase, line 225
				16:[name:'16', title:'(16) Manual Control', size:1, description:'Enable/Disable Manual Control', type:'enum', options:[0:'Disable', 1:'Enable']],  // library marker zwaveTools.zwaveDeviceDatabase, line 226
				17:[name:'17', title:'(17) LED Mode', size:1, description:'LED Indicator Mode', type:'enum', options:[0:'Always On', 1:'Follow Outlet', 2:'Momentary', 3:'Always Off']],  // library marker zwaveTools.zwaveDeviceDatabase, line 227
				18:[name:'18', title:'(18) Reports', size:1, description:'Enable/Disable Energy and USB Reports', type:'enum', options:[0:'0 - Energy and USB reports enabled ', 1:'1 - Energy and USB reports disabled', 2:'2 - Energy reports for left outlet disabled', 3:'3 - Energy reports for right outlet disabled', 4:'4 - USB reports disabled']] // library marker zwaveTools.zwaveDeviceDatabase, line 228
			] // library marker zwaveTools.zwaveDeviceDatabase, line 229
	],	 // library marker zwaveTools.zwaveDeviceDatabase, line 230
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 231
		formatVersion:1, // library marker zwaveTools.zwaveDeviceDatabase, line 232
		fingerprints: [ // library marker zwaveTools.zwaveDeviceDatabase, line 233
				[manufacturer:0x031E, deviceId: 0x0001,  deviceType:0x000E, name:"Inovelli LZW36 Light / Fan Controller"] // library marker zwaveTools.zwaveDeviceDatabase, line 234
				], // library marker zwaveTools.zwaveDeviceDatabase, line 235
		classVersions: [32:1, 34:1, 38:3, 50:3, 89:1, 90:1, 91:3, 94:1, 96:2, 112:1, 114:1, 115:1, 117:2, 122:1, 133:2, 134:2, 135:3, 142:3, 152:1], // library marker zwaveTools.zwaveDeviceDatabase, line 236
		endpoints:[ // List of classes for each endpoint. Key classes:  0x25 (37) or 0x26 (38), 0x32 (50), 0x70  (switch, dimmer, metering,  // library marker zwaveTools.zwaveDeviceDatabase, line 237
					0:[ classes:[32, 34, 38, 50, 89, 90, 91, 94, 96, 112, 114, 115, 117, 122, 133, 134, 135, 142, 152]], // library marker zwaveTools.zwaveDeviceDatabase, line 238
					1:[	children:[[type:"Generic Component Dimmer", namespace:"hubitat", childName:"LZW36 Dimmer Device"]],  // library marker zwaveTools.zwaveDeviceDatabase, line 239
						classes:[0x26] ],  // library marker zwaveTools.zwaveDeviceDatabase, line 240
					2:[	children:[[type:"Generic Component Fan Control", namespace:"hubitat", childName:"LZW36 Fan Device"]],  // library marker zwaveTools.zwaveDeviceDatabase, line 241
						classes:[0x26] ] // library marker zwaveTools.zwaveDeviceDatabase, line 242
				], // library marker zwaveTools.zwaveDeviceDatabase, line 243
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 244
				1:[size:1, name:"1", range:"0..99", title:"(1) Light Dimming Speed (Remote)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 245
				2:[size:1, name:"2", range:"0..99", title:"(2) Light Dimming Speed (From Switch)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 246
				3:[size:1, name:"3", range:"0..99", title:"(3) Light Ramp Rate (Remote)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 247
				4:[size:1, name:"4", range:"0..99", title:"(4) Light Ramp Rate (From Switch)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 248
				5:[size:1, name:"5", range:"1..45", title:"(5) Minimum Light Level", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 249
				6:[size:1, name:"6", range:"55..99", title:"(6) Maximum Light Level", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 250
				7:[size:1, name:"7", range:"1..45", title:"(7) Minimum Fan Level", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 251
				8:[size:1, name:"8", range:"55..99", title:"(8) Maximum Fan Level", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 252
				10:[size:2, name:"10", range:"0..32767", title:"(10) Auto Off Light Timer", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 253
				11:[size:2, name:"11", range:"0..32767", title:"(11) Auto Off Fan Timer", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 254
				12:[size:1, name:"12", range:"0..99", title:"(12) Default Light Level (Local)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 255
				13:[size:1, name:"13", range:"0..99", title:"(13) Default Light Level (Z-Wave)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 256
				14:[size:1, name:"14", range:"0..99", title:"(14) Default Fan Level (Local)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 257
				15:[size:1, name:"15", range:"0..99", title:"(15) Default Fan Level (Z-Wave)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 258
				16:[size:1, name:"16", range:"0..100", title:"(16) Light State After Power Restored", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 259
				17:[size:1, name:"17", range:"0..100", title:"(17) Fan State After Power Restored", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 260
				18:[size:2, name:"18", range:"0..255", title:"(18) Light LED Indicator Color", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 261
				19:[size:1, name:"19", range:"0..10", title:"(19) Light LED Strip Intensity", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 262
				20:[size:2, name:"20", range:"0..255", title:"(20) Fan LED Indicator Color", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 263
				21:[size:1, name:"21", range:"0..10", title:"(21) Fan LED Strip Intensity", type:"number"], // library marker zwaveTools.zwaveDeviceDatabase, line 264
				22:[size:1, name:"22", range:"0..10", title:"(22) Light LED Strip Intensity (When OFF)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 265
				23:[size:1, name:"23", range:"0..10", title:"(23) Fan LED Strip Intensity (When OFF)", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 266
				24:[size:4, name:"24", range:"0..83823359", title:"(24) Light LED Strip Effect", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 267
				25:[size:4, name:"25", range:"0..83823359", title:"(25) Fan LED Strip Effect", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 268
				26:[size:1, name:"26", options:[0:"Stay Off", 1:"One Second", 2:"Two Seconds", 3:"Three Seconds", 4:"Four Seconds", 5:"Five Seconds", 6:"Six Seconds", 7:"Seven Seconds", 8:"Eight Seconds", 9:"Nine Seconds", 10:"Ten Seconds"], title:"(26) Light LED Strip Timeout", type:"enum"],  // library marker zwaveTools.zwaveDeviceDatabase, line 269
				27:[size:1, name:"27", options:[0:"Stay Off", 1:"One Second", 2:"Two Seconds", 3:"Three Seconds", 4:"Four Seconds", 5:"Five Seconds", 6:"Six Seconds", 7:"Seven Seconds", 8:"Eight Seconds", 9:"Nine Seconds", 10:"Ten Seconds"], title:"(27) Fan LED Strip Timeout", type:"enum"],  // library marker zwaveTools.zwaveDeviceDatabase, line 270
				28:[size:1, name:"28", range:"0..100", title:"(28) Active Power Reports", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 271
				29:[size:2, name:"29", range:"0..32767", title:"(29) Periodic Power & Energy Reports", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 272
				30:[size:1, name:"30", range:"0..100", title:"(30) Energy Reports", type:"number"],  // library marker zwaveTools.zwaveDeviceDatabase, line 273
				31:[size:1, name:"31", options:[0:"None", 1:"Light Button", 2:"Fan Button", 3:"Both Buttons"], title:"(31) Local Protection Settings", type:"enum"],  // library marker zwaveTools.zwaveDeviceDatabase, line 274
				51:[size:1, name:"51", description:"Disable the 700ms Central Scene delay.", title:"(51) Enable instant on", options:[0:"No Delay (Central scene Disabled)", 1:"700mS Delay (Central Scene Enabled)"], type:"enum"],  // library marker zwaveTools.zwaveDeviceDatabase, line 275
		] // library marker zwaveTools.zwaveDeviceDatabase, line 276
	], // library marker zwaveTools.zwaveDeviceDatabase, line 277
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 278
		formatVersion:1, 'fingerprints':[['manufacturer':634, 'deviceId':40961, 'deviceType':40960, name:'Zooz: ZEN26']],  // library marker zwaveTools.zwaveDeviceDatabase, line 279
		classVersions:[89:1, 37:1, 142:3, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1],  // library marker zwaveTools.zwaveDeviceDatabase, line 280
		endpoints:[ 0:[ // library marker zwaveTools.zwaveDeviceDatabase, line 281
						children:[ [type:'Generic Component Switch', 'namespace':'hubitat']	], // library marker zwaveTools.zwaveDeviceDatabase, line 282
						classes:[0, 32, 37, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134, 142]] // library marker zwaveTools.zwaveDeviceDatabase, line 283
				],  // library marker zwaveTools.zwaveDeviceDatabase, line 284
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 285
			11:[size:1, name:'11', options:['0':'Local control disabled', '1':'Local control enabled'], description:'Enable or disable local ON/OFF control', title:'(11) Enable/disable paddle control', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 286
			1:[size:1, name:'1', description:'Choose paddle functionality (invert)', range:'0..1', title:'(1) Paddle control', type:'number'],  // library marker zwaveTools.zwaveDeviceDatabase, line 287
			2:[size:1, name:'2', options:['0':'LED ON when switch OFF', '1':'LED ON when switch ON', '2':'LED OFF', '3':'LED ON'], description:'Change behavior of the LED indicator', title:'(2) LED indicator control', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 288
			3:[size:1, name:'3', options:['0':'Disable', '1':'Enable'], description:'Enable/disable turn-OFF timer', title:'(3) Auto turn-OFF timer', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 289
			4:[size:4, name:'4', description:'Length of time before switch turns OFF', range:'0..65535', title:'(4) Auto turn-OFF timer length', type:'number'],  // library marker zwaveTools.zwaveDeviceDatabase, line 290
			5:[size:1, name:'5', options:['0':'Disable', '1':'Enable'], description:'Enable/disable turn-ON timer', title:'(5) Auto turn-ON timer', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 291
			6:[size:4, name:'6', description:'Length of time before switch turns ON', range:'0..65535', title:'(6) Auto turn-ON timer length', type:'number'],  // library marker zwaveTools.zwaveDeviceDatabase, line 292
			7:[size:1, name:'7', options:['11':'Physical tap ZEN26, 3-way switch or timer', '12':'Z-Wave command or timer', '13':'Physical tap ZEN26, Z-Wave or timer', '14':'Physical tap ZEN26, 3-way switch, Z-Wave or timer', '15':'All of the above', '0':'None', '1':'Physical tap ZEN26 only', '2':'Physical tap 3-way switch only', '3':'Physical tap ZEN26 or 3-way switch', '4':'Z-Wave command', '5':'Physical tap ZEN26 or Z-Wave', '6':'Physical tap 3-way switch or Z-Wave', '7':'Physical tap ZEN26, 3-way switch or Z-Wave', '8':'Timer only', '9':'Physical tap ZEN26 or timer', '10':'Physical tap 3-way switch or timer'], title:'(7) Association reports', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 293
			8:[size:1, name:'8', options:['0':'OFF', '1':'ON', '2':'Restore last state'], description:'Set the ON/OFF status for the switch after power failure', title:'(8) ON/OFF status after power failure', type:'enum'],  // library marker zwaveTools.zwaveDeviceDatabase, line 294
			10:[size:1, name:'10', options:['0':'Scene control disabled', '1':'Scene control enabled'], title:'(10) Enable/disable scene control', type:'enum'] // library marker zwaveTools.zwaveDeviceDatabase, line 295
		] // library marker zwaveTools.zwaveDeviceDatabase, line 296
	],	 // library marker zwaveTools.zwaveDeviceDatabase, line 297
	[ // library marker zwaveTools.zwaveDeviceDatabase, line 298
		formatVersion:1,  // library marker zwaveTools.zwaveDeviceDatabase, line 299
		fingerprints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 300
				['manufacturer':634, 'deviceId':40968, 'deviceType':40960, name:'Zooz: ZEN30'] // Zooz Zen 30 // library marker zwaveTools.zwaveDeviceDatabase, line 301
			],  // library marker zwaveTools.zwaveDeviceDatabase, line 302
		classVersions:[0:1, 32:1, 37:1, 38:3, 89:1, 90:1, 91:3, 94:1, 96:2, 112:1, 114:1, 115:1, 122:1, 133:2, 134:2, 142:3, 152:2],  // library marker zwaveTools.zwaveDeviceDatabase, line 303
		endpoints:[ // library marker zwaveTools.zwaveDeviceDatabase, line 304
					0:[ // library marker zwaveTools.zwaveDeviceDatabase, line 305
						children:[ [type:'Generic Component Dimmer', 'namespace':'hubitat']	], // library marker zwaveTools.zwaveDeviceDatabase, line 306
						classes:[0, 32, 37, 38, 89, 90, 91, 94, 96, 112, 114, 115, 122, 133, 134, 142, 152]],  // library marker zwaveTools.zwaveDeviceDatabase, line 307
					1:[ children:[[type:'Generic Component Switch', namespace:'hubitat', childName:"Relay Switch"]],  // library marker zwaveTools.zwaveDeviceDatabase, line 308
						classes:[32, 37, 89, 94, 133, 142]] // library marker zwaveTools.zwaveDeviceDatabase, line 309
				],  // library marker zwaveTools.zwaveDeviceDatabase, line 310
		deviceInputs:[ // library marker zwaveTools.zwaveDeviceDatabase, line 311
			1:[name:'1', title:'(1) LED Indicator Mode for Dimmer', size:1, type:'enum', options:[0:'ON when switch is OFF and OFF when switch is ON', 1:'ON when switch is ON and OFF when switch is OFF', 2:'LED indicator is always OFF', 3:'LED indicator is always ON']],  // library marker zwaveTools.zwaveDeviceDatabase, line 312
			2:[name:'2', title:'(2) LED Indicator Control for Relay', size:1, type:'enum', options:[0:'ON when relay is OFF and OFF when relay is ON', 1:'ON when relay is ON and OFF when relay is OFF', 2:'LED indicator is always OFF', 3:'LED indicator is always ON']],  // library marker zwaveTools.zwaveDeviceDatabase, line 313
			3:[name:'3', title:'(3) LED Indicator Color for Dimmer', size:1, description:'Choose the color of the LED indicators for the dimmer', type:'enum', options:[0:'White (default)', 1:'Blue', 2:'Green', 3:'Red']],  // library marker zwaveTools.zwaveDeviceDatabase, line 314
			4:[name:'4', title:'(4) LED Indicator Color for Relay', size:1, type:'enum', options:[0:'White (default)', 1:'Blue', 2:'Green', 3:'Red']],  // library marker zwaveTools.zwaveDeviceDatabase, line 315
			5:[name:'5', title:'(5) LED Indicator Brightness for Dimmer', size:1, type:'enum', options:[0:'Bright (100%)', 1:'Medium (60%)', 2:'Low (30% - default)']],  // library marker zwaveTools.zwaveDeviceDatabase, line 316
			6:[name:'6', title:'(6) LED Indicator Brightness for Relay', size:1, type:'enum', options:[0:'Bright (100%)', 1:'Medium (60%)', 2:'Low (30% - default)']],  // library marker zwaveTools.zwaveDeviceDatabase, line 317
			7:[name:'7', title:'(7) LED Indicator Mode for Scene Control', size:1, type:'enum', options:[0:'Enabled to indicate scene triggers', 1:'Disabled to indicate scene triggers (default)']],  // library marker zwaveTools.zwaveDeviceDatabase, line 318
			8:[name:'8', title:'(8) Auto Turn-Off Timer for Dimmer', size:4, type:'number', range:"0..65535"],  // library marker zwaveTools.zwaveDeviceDatabase, line 319
			9:[name:'9', title:'(9) Auto Turn-On Timer for Dimmer', size:4, type:'number', range:"0..65535"],  // library marker zwaveTools.zwaveDeviceDatabase, line 320
			10:[name:'10', title:'(10) Auto Turn-Off Timer for Relay', size:4, type:'number', range:"0..65535"],  // library marker zwaveTools.zwaveDeviceDatabase, line 321
			11:[name:'11', title:'(11) Auto Turn-On Timer for Relay', size:4, type:'number', range:"0..65535"],  // library marker zwaveTools.zwaveDeviceDatabase, line 322
			12:[name:'12', title:'(12) On Off Status After Power Failure', size:1, type:'enum', options:[0:'Dimmer and relay forced to OFF', 1:'Dimmer forced to OFF, relay forced to ON', 2:'Dimmer forced to ON, relay forced to OFF', 3:'Restores status for dimmer and relay (default)', 4:'Restores status for dimmer, relay forced to ON', 5:'Restores status for dimmer, relay forced to OFF', 6:'Dimmer forced to ON, restores status for relay', 7:'Dimmer forced to OFF, restores status for relay', 8:'Dimmer and relay forced to ON']],  // library marker zwaveTools.zwaveDeviceDatabase, line 323
			13:[name:'13', title:'(13) Ramp Rate Control for Dimmer', size:1, type:'number', range:"0..99"],  // library marker zwaveTools.zwaveDeviceDatabase, line 324
			14:[name:'14', title:'(14) Minimum Brightness', size:1, type:'number', range:"1..99"],  // library marker zwaveTools.zwaveDeviceDatabase, line 325
			15:[name:'15', title:'(15) Maximum Brightness', size:1, type:'number', range:"1..99"],  // library marker zwaveTools.zwaveDeviceDatabase, line 326
			17:[name:'17', title:'(17) Double Tap Function for Dimmer', size:1, type:'enum', options:[0:'ON to full brightness with double tap (default)', 1:'ON to brightness set in #15 with double tap']],  // library marker zwaveTools.zwaveDeviceDatabase, line 327
			18:[name:'18', title:'(18) Disable Double Tap', size:1, type:'enum', options:[0:'Full/max brightness level enabled (default)', 1:'Disabled, single tap for last brightness', 2:'Disabled, single tap to full brightness']],  // library marker zwaveTools.zwaveDeviceDatabase, line 328
			19:[name:'19', title:'(19) Smart Bulb Setting', size:1, description:'Enable/Disable Load Control for Dimmer', type:'enum', options:[0:'Manual control disabled', 1:'Manual control enabled (default)', 2:'Manual and Z-Wave control disabled']],  // library marker zwaveTools.zwaveDeviceDatabase, line 329
			20:[name:'20', title:'(20) Remote Control Setting', size:1, description:'Enable/Disable Load Control for Relay', type:'enum', options:[0:'Manual control disabled', 1:'Manual control enabled (default)', 2:'Manual and Z-Wave control disabled']],  // library marker zwaveTools.zwaveDeviceDatabase, line 330
			21:[name:'21', title:'(21) Manual Dimming Speed', size:1, type:'number', range:"1..99"],  // library marker zwaveTools.zwaveDeviceDatabase, line 331
			// 22:[name:'22', title:'(22) Z-Wave Ramp Rate for Dimmer', size:1, type:'enum', options:[0:'Match #13', 1:'Set through Command Class']],  // library marker zwaveTools.zwaveDeviceDatabase, line 332
			23:[name:'23', title:'(23) Default Brightness Level ON for Dimmer', size:1, type:'number', range:"0..99"]	 // library marker zwaveTools.zwaveDeviceDatabase, line 333
		] // library marker zwaveTools.zwaveDeviceDatabase, line 334
	]	 // library marker zwaveTools.zwaveDeviceDatabase, line 335
] // library marker zwaveTools.zwaveDeviceDatabase, line 336

// ~~~~~ end include (34) zwaveTools.zwaveDeviceDatabase ~~~~~

// ~~~~~ start include (33) zwaveTools.notificationTools ~~~~~
library ( // library marker zwaveTools.notificationTools, line 1
        base: "driver", // library marker zwaveTools.notificationTools, line 2
        author: "jvm33", // library marker zwaveTools.notificationTools, line 3
        category: "zwave", // library marker zwaveTools.notificationTools, line 4
        description: "Handles Zwave Notifications", // library marker zwaveTools.notificationTools, line 5
        name: "notificationTools", // library marker zwaveTools.notificationTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.notificationTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.notificationTools, line 8
		version: "0.0.1", // library marker zwaveTools.notificationTools, line 9
		dependencies: "zwaveTools.hubTools", // library marker zwaveTools.notificationTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/notificationTools.groovy" // library marker zwaveTools.notificationTools, line 11
) // library marker zwaveTools.notificationTools, line 12
/* // library marker zwaveTools.notificationTools, line 13
Relevant Z-Wave standards // library marker zwaveTools.notificationTools, line 14
SDS13713 = Silicon Labs Zwave Standard // library marker zwaveTools.notificationTools, line 15
*/ // library marker zwaveTools.notificationTools, line 16
////////////////////////////////////////////////////////////////////// // library marker zwaveTools.notificationTools, line 17
//////        Handle   Notifications     /////// // library marker zwaveTools.notificationTools, line 18
////////////////////////////////////////////////////////////////////// // library marker zwaveTools.notificationTools, line 19


void	notificationTools_refresh(Integer ep = null ) { // library marker zwaveTools.notificationTools, line 22
	Map specifiedNotifications = getEndpointNotificationsSupported(ep) // library marker zwaveTools.notificationTools, line 23
	if (specifiedNotifications) // library marker zwaveTools.notificationTools, line 24
	{  // library marker zwaveTools.notificationTools, line 25
		specifiedNotifications.each{type, events -> // library marker zwaveTools.notificationTools, line 26
				performRefreshByType(type, events, ep) // library marker zwaveTools.notificationTools, line 27
				} // library marker zwaveTools.notificationTools, line 28
	}	else  { // library marker zwaveTools.notificationTools, line 29
		basicZwaveSend(zwave.notificationV8.notificationSupportedGet(), ep) // library marker zwaveTools.notificationTools, line 30
	} // library marker zwaveTools.notificationTools, line 31
} // library marker zwaveTools.notificationTools, line 32

void performRefreshByType(Integer type, List<Integer> events, Integer ep) // library marker zwaveTools.notificationTools, line 34
{ // library marker zwaveTools.notificationTools, line 35
	// type is a single integer item corrensponding to Column B of zwave standard SDS13713 // library marker zwaveTools.notificationTools, line 36
	//	Events is a list of integers identifying the sub-events for the type. This correspondes to column G of zwave standard SDS13713. // library marker zwaveTools.notificationTools, line 37
	events.each{ it -> // library marker zwaveTools.notificationTools, line 38
		basicZwaveSend(zwave.notificationV8.notificationGet(v1AlarmType:0, event:(Integer) it , notificationType: type), ep) // library marker zwaveTools.notificationTools, line 39
	} // library marker zwaveTools.notificationTools, line 40
} // library marker zwaveTools.notificationTools, line 41

List<Integer> getNotificationTypesList(def cmd) { // library marker zwaveTools.notificationTools, line 43
	List<Integer> notificationTypes = [] // library marker zwaveTools.notificationTools, line 44

	if (cmd.smoke)				notificationTypes += 1 // Smoke // library marker zwaveTools.notificationTools, line 46
	if (cmd.co)					notificationTypes += 2 // CO // library marker zwaveTools.notificationTools, line 47
	if (cmd.co2)				notificationTypes += 3 // CO2 // library marker zwaveTools.notificationTools, line 48
	if (cmd.heat)				notificationTypes += 4 // Heat // library marker zwaveTools.notificationTools, line 49
	if (cmd.water)				notificationTypes += 5 // Water // library marker zwaveTools.notificationTools, line 50
	if (cmd.accessControl) 		notificationTypes += 6 // Access Control // library marker zwaveTools.notificationTools, line 51
	if (cmd.burglar)			notificationTypes += 7 // Burglar // library marker zwaveTools.notificationTools, line 52
	if (cmd.powerManagement)	notificationTypes += 8 // Power Management // library marker zwaveTools.notificationTools, line 53
	if (cmd.system)				notificationTypes += 9 // System // library marker zwaveTools.notificationTools, line 54
	if (cmd.emergency)			notificationTypes += 10 // Emergency Alarm // library marker zwaveTools.notificationTools, line 55
	if (cmd.clock)				notificationTypes += 11 // Clock // library marker zwaveTools.notificationTools, line 56
	if (cmd.appliance)			notificationTypes += 12 // Appliance // library marker zwaveTools.notificationTools, line 57
	if (cmd.homeHealth)			notificationTypes += 13 // Home Health // library marker zwaveTools.notificationTools, line 58
	if (cmd.siren)				notificationTypes += 14 // Siren // library marker zwaveTools.notificationTools, line 59
	if (cmd.waterValve)			notificationTypes += 15 // Water Valve // library marker zwaveTools.notificationTools, line 60
	if (cmd.weatherAlarm)		notificationTypes += 16 // Weather Alarm // library marker zwaveTools.notificationTools, line 61
	if (cmd.irrigation)			notificationTypes += 17 // Irrigation // library marker zwaveTools.notificationTools, line 62
	if (cmd.gasAlarm)			notificationTypes += 18 // Gas Alarm // library marker zwaveTools.notificationTools, line 63
	if (cmd.pestControl)		notificationTypes += 19 // Pest Control // library marker zwaveTools.notificationTools, line 64
	if (cmd.lightSensor)		notificationTypes += 20 // Light Sensor // library marker zwaveTools.notificationTools, line 65
	if (cmd.waterQualityMonitoring)		notificationTypes += 21 // Water Quality // library marker zwaveTools.notificationTools, line 66
	if (cmd.homeMonitoring)		notificationTypes += 22 // Home Monitoring // library marker zwaveTools.notificationTools, line 67
} // library marker zwaveTools.notificationTools, line 68

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationSupportedReport report, Integer ep = null ) // library marker zwaveTools.notificationTools, line 70
{  // library marker zwaveTools.notificationTools, line 71
	getNotificationTypesList(report).each{it ->  // library marker zwaveTools.notificationTools, line 72
			basicZwaveSend(zwave.notificationV8.eventSupportedGet(notificationType:(Integer) it), ep)} // library marker zwaveTools.notificationTools, line 73
} // library marker zwaveTools.notificationTools, line 74

void zwaveEvent(hubitat.zwave.commands.notificationv8.EventSupportedReport cmd, Integer ep = null ) // library marker zwaveTools.notificationTools, line 76
{ // library marker zwaveTools.notificationTools, line 77
	// Build a map of the notifications supported by a device endpoint and store it in the endpoint data // library marker zwaveTools.notificationTools, line 78
	List supportedEventsByType = cmd.supportedEvents.findAll{k, v -> ((v as Boolean) == true) }.collect{key, value -> (Integer) key  } // library marker zwaveTools.notificationTools, line 79
	getEndpointNotificationsSupported(ep).put( (Integer) cmd.notificationType , supportedEventsByType) // library marker zwaveTools.notificationTools, line 80
} // library marker zwaveTools.notificationTools, line 81

Map getFormattedZWaveNotificationEvent(def cmd) // library marker zwaveTools.notificationTools, line 83
{ // library marker zwaveTools.notificationTools, line 84
	Date currentDate = new Date() // library marker zwaveTools.notificationTools, line 85
	Map notificationEvent = // library marker zwaveTools.notificationTools, line 86
		[ 	0x01:[ // Smoke // library marker zwaveTools.notificationTools, line 87
				0:[	 // library marker zwaveTools.notificationTools, line 88
						1:[name:"smoke" , value:"clear", descriptionText:"Smoke detected (location provided) status Idle."], // library marker zwaveTools.notificationTools, line 89
						2:[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."], // library marker zwaveTools.notificationTools, line 90
						4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				 // library marker zwaveTools.notificationTools, line 91
						5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				 // library marker zwaveTools.notificationTools, line 92
						7:[name:"consumableStatus" , value:"good", descriptionText:"Periodic Maintenance Not Due"],				 // library marker zwaveTools.notificationTools, line 93
						8:[name:"consumableStatus" , value:"good", descriptionText:"No Dust in device - clear."], // library marker zwaveTools.notificationTools, line 94
					],  // library marker zwaveTools.notificationTools, line 95
				1:[name:"smoke" , value:"detected", descriptionText:"Smoke detected (location provided)."],  // library marker zwaveTools.notificationTools, line 96
				2:[name:"smoke" , value:"detected", descriptionText:"Smoke detected."], // library marker zwaveTools.notificationTools, line 97
				4:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required."],				 // library marker zwaveTools.notificationTools, line 98
				5:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."],				 // library marker zwaveTools.notificationTools, line 99
				7:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."],				 // library marker zwaveTools.notificationTools, line 100
				8:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."], // library marker zwaveTools.notificationTools, line 101
				], // library marker zwaveTools.notificationTools, line 102
			0x02:[ // CO // library marker zwaveTools.notificationTools, line 103
				0:[ // library marker zwaveTools.notificationTools, line 104
						1:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."], // library marker zwaveTools.notificationTools, line 105
						2:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],	 // library marker zwaveTools.notificationTools, line 106
						4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				 // library marker zwaveTools.notificationTools, line 107
						5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				 // library marker zwaveTools.notificationTools, line 108
						7:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance required cleared, periodic inspection."],				 // library marker zwaveTools.notificationTools, line 109
					],  // library marker zwaveTools.notificationTools, line 110
				1:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected (location provided)."],  // library marker zwaveTools.notificationTools, line 111
				2:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."], // library marker zwaveTools.notificationTools, line 112
				4:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."],				 // library marker zwaveTools.notificationTools, line 113
				5:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."],				 // library marker zwaveTools.notificationTools, line 114
				7:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."], // library marker zwaveTools.notificationTools, line 115
				], // library marker zwaveTools.notificationTools, line 116
			0x03:[ // CO2 // library marker zwaveTools.notificationTools, line 117
				0:[ // library marker zwaveTools.notificationTools, line 118
						1:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."], // library marker zwaveTools.notificationTools, line 119
						2:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],	 // library marker zwaveTools.notificationTools, line 120
						4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				 // library marker zwaveTools.notificationTools, line 121
						5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				 // library marker zwaveTools.notificationTools, line 122
						7:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)."],				 // library marker zwaveTools.notificationTools, line 123
					],  // library marker zwaveTools.notificationTools, line 124
				1:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected (location provided)."],  // library marker zwaveTools.notificationTools, line 125
				2:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."], // library marker zwaveTools.notificationTools, line 126
				4:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."],				 // library marker zwaveTools.notificationTools, line 127
				5:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."],				 // library marker zwaveTools.notificationTools, line 128
				7:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."], // library marker zwaveTools.notificationTools, line 129
				], // library marker zwaveTools.notificationTools, line 130
			0x04:[ // Heat Alarm - requires custom attribute heatAlarm // library marker zwaveTools.notificationTools, line 131
				0:[ // library marker zwaveTools.notificationTools, line 132
						1:[name:"heatAlarm" , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."], // library marker zwaveTools.notificationTools, line 133
						2:[name:"heatAlarm" , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."], // library marker zwaveTools.notificationTools, line 134
						5:[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."], // library marker zwaveTools.notificationTools, line 135
						6:[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."], // library marker zwaveTools.notificationTools, line 136
						8:[name:"consumableStatus " , value:"good", descriptionText:"Replacement required (End-of-Life)."],				 // library marker zwaveTools.notificationTools, line 137
						10:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)"],				 // library marker zwaveTools.notificationTools, line 138
						11:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)"], // library marker zwaveTools.notificationTools, line 139
						12:[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."], // library marker zwaveTools.notificationTools, line 140
						13:[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."],	 // library marker zwaveTools.notificationTools, line 141
				],  // library marker zwaveTools.notificationTools, line 142
				1:[name:"heatAlarm" , value:"overheat", descriptionText:"Overheat detected, Location Provided."], // library marker zwaveTools.notificationTools, line 143
				2:[name:"heatAlarm" , value:"overheat", descriptionText:"Overheat detected, Unknown Location."], // library marker zwaveTools.notificationTools, line 144
				3:[name:"heatAlarm " , value:"rapidRise", descriptionText:"Rapid Temperature Rise detected, Location Provided."], // library marker zwaveTools.notificationTools, line 145
				4:[name:"heatAlarm " , value:"rapidRise", descriptionText:"Rapid Temperature Rise detected, Unknown Location."],				 // library marker zwaveTools.notificationTools, line 146
				5:[name:"heatAlarm " , value:"underheat", descriptionText:"Underheat detected, Location Provided."], // library marker zwaveTools.notificationTools, line 147
				6:[name:"heatAlarm " , value:"underheat", descriptionText:"Underheat detected, Unknown Location."], // library marker zwaveTools.notificationTools, line 148
				8:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."],				 // library marker zwaveTools.notificationTools, line 149
				10:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."],				 // library marker zwaveTools.notificationTools, line 150
				11:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."], // library marker zwaveTools.notificationTools, line 151
				12:[name:"heatAlarm " , value:"rapidFall", descriptionText:"Rapid Temperature Fall detected, Location Provided."], // library marker zwaveTools.notificationTools, line 152
				13:[name:"heatAlarm " , value:"rapidFall", descriptionText:"Rapid Temperature Fall detected, Unknown Location."],				 // library marker zwaveTools.notificationTools, line 153
				],				 // library marker zwaveTools.notificationTools, line 154
			0x05:[ // Water	 // library marker zwaveTools.notificationTools, line 155
				0:[ // library marker zwaveTools.notificationTools, line 156
						1:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."], // library marker zwaveTools.notificationTools, line 157
						2:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."], // library marker zwaveTools.notificationTools, line 158
						5:[name:"filterStatus " , value:"normal", descriptionText:"Water filter good."],				 // library marker zwaveTools.notificationTools, line 159

				],  // library marker zwaveTools.notificationTools, line 161
				1:[name:"water" , value:"wet", descriptionText:"Water leak detected (location provided)."],  // library marker zwaveTools.notificationTools, line 162
				2:[name:"water" , value:"wet", descriptionText:"Water leak detected."], // library marker zwaveTools.notificationTools, line 163
				5:[name:"filterStatus " , value:"replace", descriptionText:"Replace water filter (End-of-Life)."],				 // library marker zwaveTools.notificationTools, line 164

				], // library marker zwaveTools.notificationTools, line 166
			0x06:[ // Access Control (Locks) // library marker zwaveTools.notificationTools, line 167
				0:[],  // library marker zwaveTools.notificationTools, line 168
				1:[name:"lock" , value:"locked", descriptionText:"Manual lock operation"],  // library marker zwaveTools.notificationTools, line 169
				2:[name:"lock" , value:"unlocked", descriptionText:"Manual unlock operation"],  // library marker zwaveTools.notificationTools, line 170
				3:[name:"lock" , value:"locked", descriptionText:"RF lock operation"],  // library marker zwaveTools.notificationTools, line 171
				4:[name:"lock" , value:"unlocked", descriptionText:"RF unlock operation"],  // library marker zwaveTools.notificationTools, line 172
				5:[name:"lock" , value:"locked", descriptionText:"Keypad lock operation"],  // library marker zwaveTools.notificationTools, line 173
				6:[name:"lock" , value:"unlocked", descriptionText:"Keypad unlock operation"],  // library marker zwaveTools.notificationTools, line 174
				11:[name:"lock" , value:"unknown", descriptionText:"Lock jammed"], 				 // library marker zwaveTools.notificationTools, line 175
				254:[name:"lock" , value:"unknown", descriptionText:"Lock in unknown state"] // library marker zwaveTools.notificationTools, line 176
				], // library marker zwaveTools.notificationTools, line 177
			0x07:[ // Home Security // library marker zwaveTools.notificationTools, line 178
				0:[	 // These events "clear" a sensor.	 // library marker zwaveTools.notificationTools, line 179
						1:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed (location provided)"],  // library marker zwaveTools.notificationTools, line 180
						2:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed"], 					 // library marker zwaveTools.notificationTools, line 181
						3:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."], // library marker zwaveTools.notificationTools, line 182
						4:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."], // library marker zwaveTools.notificationTools, line 183
						5:[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected (location provided)"], // glass Breakage  attribute! // library marker zwaveTools.notificationTools, line 184
						6:[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected"], 	 // glass Breakage attribute!					 // library marker zwaveTools.notificationTools, line 185
						7:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."], // library marker zwaveTools.notificationTools, line 186
						8:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."], // library marker zwaveTools.notificationTools, line 187
						9:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."], // library marker zwaveTools.notificationTools, line 188

					],  // library marker zwaveTools.notificationTools, line 190
				1:[name:"contact" , value:"open", descriptionText:"Contact sensor, open (location provided)"], 	 // library marker zwaveTools.notificationTools, line 191
				2:[name:"contact" , value:"open", descriptionText:"Contact sensor, open"], 					 // library marker zwaveTools.notificationTools, line 192
				3:[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"],  // library marker zwaveTools.notificationTools, line 193
				4:[name:"tamper" , value:"detected", descriptionText:"Tampering, invalid code."],  // library marker zwaveTools.notificationTools, line 194
				5:[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected (location provided)"],  // library marker zwaveTools.notificationTools, line 195
				6:[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected"], 				 // library marker zwaveTools.notificationTools, line 196
				7:[name:"motion" , value:"active", descriptionText:"Motion detected (location provided)."], // library marker zwaveTools.notificationTools, line 197
				8:[name:"motion" , value:"active", descriptionText:"Motion detected."], // library marker zwaveTools.notificationTools, line 198
				9:[name:"tamper" , value:"detected", descriptionText:"Tampering, device moved"] // library marker zwaveTools.notificationTools, line 199
				], // library marker zwaveTools.notificationTools, line 200
			0x08:[ // Power Management // library marker zwaveTools.notificationTools, line 201
				0:[ // These events "clear" a sensor // library marker zwaveTools.notificationTools, line 202
					5:[name:"powerSource" , value:"unknown", descriptionText:"Voltage drop/drift cleared"], // library marker zwaveTools.notificationTools, line 203
					], // library marker zwaveTools.notificationTools, line 204
				1:[name:"powerSource" , value:"unknown", descriptionText:"Power applied"], // library marker zwaveTools.notificationTools, line 205
				], // library marker zwaveTools.notificationTools, line 206
			0x09:[ // System // library marker zwaveTools.notificationTools, line 207
				0:[ // These events "clear" a sensor // library marker zwaveTools.notificationTools, line 208
					4:[name:"softwareFailure" , value:"cleared", descriptionText:"System Software Report - Startup Cleared"], // library marker zwaveTools.notificationTools, line 209
					5:[name:"heartbeat" , value:currentDate, descriptionText:"Last Heartbeat"], // library marker zwaveTools.notificationTools, line 210
					], // library marker zwaveTools.notificationTools, line 211
				4:[name:"softwareFailure" , value:(cmd.notificationStatus), descriptionText:"System Software Failure Report - Proprietary Code"], // library marker zwaveTools.notificationTools, line 212
				5:[name:"heartbeat" , value:currentDate, descriptionText:"Last Heartbeat"] // library marker zwaveTools.notificationTools, line 213
				], 				 // library marker zwaveTools.notificationTools, line 214
			0x12:[ //  Gas Alarm // library marker zwaveTools.notificationTools, line 215
				0:[	 // These events "clear" a sensor.	 // library marker zwaveTools.notificationTools, line 216
						1:[name:"naturalGas" , value:"clear", descriptionText:"Combustible gas (cleared) (location provided)"], 	 // library marker zwaveTools.notificationTools, line 217
						2:[name:"naturalGas" , value:"clear", descriptionText:"Combustible gas  (cleared) "],  // library marker zwaveTools.notificationTools, line 218
						5:[name:"naturalGas" , value:"clear", descriptionText:"Gas detector test completed."], 	 // library marker zwaveTools.notificationTools, line 219
						6:[name:"consumableStatus" , value:"good", descriptionText:"Gas detector (good)"], // library marker zwaveTools.notificationTools, line 220
					],  // library marker zwaveTools.notificationTools, line 221
				1:[name:"naturalGas" , value:"detected", descriptionText:"Combustible gas detected (location provided)"], 	 // library marker zwaveTools.notificationTools, line 222
				2:[name:"naturalGas" , value:"detected", descriptionText:"Combustible gas detected"],  // library marker zwaveTools.notificationTools, line 223
				5:[name:"naturalGas" , value:"tested", descriptionText:"Gas detector test"], 	 // library marker zwaveTools.notificationTools, line 224
				6:[name:"consumableStatus" , value:"replace", descriptionText:"Gas detector, replacement required"], // library marker zwaveTools.notificationTools, line 225
				],				 // library marker zwaveTools.notificationTools, line 226
			0x0E:[ // Siren // library marker zwaveTools.notificationTools, line 227
				0:[ // library marker zwaveTools.notificationTools, line 228
						1:[name:"alarm" , value:"off", descriptionText:"Alarm Siren Off."] // library marker zwaveTools.notificationTools, line 229
					],  // library marker zwaveTools.notificationTools, line 230
				1:[name:"alarm" , value:"siren", descriptionText:"Alarm Siren On."] // library marker zwaveTools.notificationTools, line 231
				],  // library marker zwaveTools.notificationTools, line 232
			0x0F:[ // Water Valve // library marker zwaveTools.notificationTools, line 233
				0:[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Valve Operation."],  // library marker zwaveTools.notificationTools, line 234
				1:[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Master Valve Operation."]  // library marker zwaveTools.notificationTools, line 235
				],  // library marker zwaveTools.notificationTools, line 236

			0x16:[ // Home Monitoring // library marker zwaveTools.notificationTools, line 238
				0:[ // library marker zwaveTools.notificationTools, line 239
						1:[name:"presence" , value:"not present", descriptionText:"Home not occupied"], // library marker zwaveTools.notificationTools, line 240
						2:[name:"presence" , value:"not present", descriptionText:"Home not occupied"] // library marker zwaveTools.notificationTools, line 241
					],  // library marker zwaveTools.notificationTools, line 242
				1:[name:"presence" , value:"present", descriptionText:"Home occupied (location provided)"],   // library marker zwaveTools.notificationTools, line 243
				2:[name:"presence" , value:"present", descriptionText:"Home occupied"] // library marker zwaveTools.notificationTools, line 244
				] // library marker zwaveTools.notificationTools, line 245

		].get((Integer) cmd.notificationType )?.get((Integer) cmd.event ) // library marker zwaveTools.notificationTools, line 247

		if (! notificationEvent) return // library marker zwaveTools.notificationTools, line 249

		if ((cmd.event == 0) && (cmd.eventParametersLength == 1)) { // This is for clearing events. // library marker zwaveTools.notificationTools, line 251
				return notificationEvent.get( (Integer) cmd.eventParameter[0] ) // library marker zwaveTools.notificationTools, line 252
		} // library marker zwaveTools.notificationTools, line 253

		if (cmd.eventParametersLength > 1) { // This is unexpected! None of the current notifications use this. // library marker zwaveTools.notificationTools, line 255
			log.error "In function getZWaveNotificationEvent(), received command with eventParametersLength of unexpected size." // library marker zwaveTools.notificationTools, line 256
			return null // library marker zwaveTools.notificationTools, line 257
		}  // library marker zwaveTools.notificationTools, line 258
		return notificationEvent // library marker zwaveTools.notificationTools, line 259
} // library marker zwaveTools.notificationTools, line 260

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, Integer ep = null ) // library marker zwaveTools.notificationTools, line 262
{ // library marker zwaveTools.notificationTools, line 263
	Map thisEvent = getFormattedZWaveNotificationEvent(cmd) // library marker zwaveTools.notificationTools, line 264
	if (thisEvent) { sendEventToEndpoints(event:thisEvent, ep:ep, alwaysSend:["heartbeat"])  // library marker zwaveTools.notificationTools, line 265
	} else { // library marker zwaveTools.notificationTools, line 266
		log.debug "Unhandled notification command report ${cmd}" // library marker zwaveTools.notificationTools, line 267
	} // library marker zwaveTools.notificationTools, line 268
} // library marker zwaveTools.notificationTools, line 269

// ~~~~~ end include (33) zwaveTools.notificationTools ~~~~~

// ~~~~~ start include (67) zwaveTools.meterTools ~~~~~
library ( // library marker zwaveTools.meterTools, line 1
        base: "driver", // library marker zwaveTools.meterTools, line 2
        author: "jvm33", // library marker zwaveTools.meterTools, line 3
        category: "zwave", // library marker zwaveTools.meterTools, line 4
        description: "Tools to Handle Zwave Meter Reports and Refreshes", // library marker zwaveTools.meterTools, line 5
        name: "meterTools", // library marker zwaveTools.meterTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.meterTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.meterTools, line 8
		version: "0.0.1", // library marker zwaveTools.meterTools, line 9
		dependencies: "zwaveTools.hubTools", // library marker zwaveTools.meterTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/meterTools.groovy" // library marker zwaveTools.meterTools, line 11
) // library marker zwaveTools.meterTools, line 12
////////////////////////////////////////////////////////////////////// // library marker zwaveTools.meterTools, line 13
//////                         Handle  Meters                  /////// // library marker zwaveTools.meterTools, line 14
////////////////////////////////////////////////////////////////////// // library marker zwaveTools.meterTools, line 15

void	meterTools_refresh(ep = null ) { // library marker zwaveTools.meterTools, line 17
	// To make driver more generic, if meter type isn't known, then ask the device // library marker zwaveTools.meterTools, line 18
	List<Integer> specifiedScales = getEndpointMetersSupported(ep) // library marker zwaveTools.meterTools, line 19
	if (specifiedScales) {  // library marker zwaveTools.meterTools, line 20
		meterTools_refreshScales(specifiedScales, ep) // library marker zwaveTools.meterTools, line 21
	}	else  { // library marker zwaveTools.meterTools, line 22
		advancedZwaveSend(zwave.meterV6.meterSupportedGet(), ep) // library marker zwaveTools.meterTools, line 23
	} // library marker zwaveTools.meterTools, line 24
} // library marker zwaveTools.meterTools, line 25

void meterTools_refreshScales( List supportedScales, Integer ep = null )  // library marker zwaveTools.meterTools, line 27
{ // meterSupported is a List of supported scales - e.g., [2, 5, 6]] // library marker zwaveTools.meterTools, line 28
	if (logEnable) log.debug "Device ${device.displayName}: Refreshing meter endpoint ${ep ?: 0} with scales ${supportedScales}" // library marker zwaveTools.meterTools, line 29

	supportedScales.each{ scaleValue -> // library marker zwaveTools.meterTools, line 31
		if ((scaleValue as Integer) <= 6) { // library marker zwaveTools.meterTools, line 32
			advancedZwaveSend(zwave.meterV6.meterGet(scale: scaleValue), ep) // library marker zwaveTools.meterTools, line 33
		} else { // library marker zwaveTools.meterTools, line 34
			advancedZwaveSend(zwave.meterV6.meterGet(scale: 7, scale2: (scaleValue - 7) ), ep) // library marker zwaveTools.meterTools, line 35
		} // library marker zwaveTools.meterTools, line 36
	} // library marker zwaveTools.meterTools, line 37
} // library marker zwaveTools.meterTools, line 38

List<Integer> getMeterScalesAsList(hubitat.zwave.commands.meterv6.MeterSupportedReport report){ // library marker zwaveTools.meterTools, line 40
	List<Integer> returnScales = [] // library marker zwaveTools.meterTools, line 41
	if (( report.scaleSupported & 0b00000001 ) as Boolean ) { returnScales.add(0) } // kWh // library marker zwaveTools.meterTools, line 42
	if (( report.scaleSupported & 0b00000010 ) as Boolean ) { returnScales.add(1) } // kVAh // library marker zwaveTools.meterTools, line 43
	if (( report.scaleSupported & 0b00000100 ) as Boolean ) { returnScales.add(2) } // Watts // library marker zwaveTools.meterTools, line 44
	if (( report.scaleSupported & 0b00001000 ) as Boolean ) { returnScales.add(3) } // PulseCount // library marker zwaveTools.meterTools, line 45
	if (( report.scaleSupported & 0b00010000 ) as Boolean ) { returnScales.add(4) } // Volts // library marker zwaveTools.meterTools, line 46
	if (( report.scaleSupported & 0b00100000 ) as Boolean ) { returnScales.add(5) } // Amps // library marker zwaveTools.meterTools, line 47
	if (( report.scaleSupported & 0b01000000 ) as Boolean ) { returnScales.add(6) } // PowerFactor // library marker zwaveTools.meterTools, line 48

	if ((( report.scaleSupported & 0b10000000 ) as Boolean ) && report.moreScaleTypes) { // library marker zwaveTools.meterTools, line 50
		if (( report.scaleSupportedBytes[1] & 0b00000001 ) as Boolean) { returnScales.add(7)} // kVar // library marker zwaveTools.meterTools, line 51
		if (( report.scaleSupportedBytes[1] & 0b00000010 ) as Boolean) { returnScales.add(8)} // kVarh // library marker zwaveTools.meterTools, line 52
	} // library marker zwaveTools.meterTools, line 53
	return returnScales // library marker zwaveTools.meterTools, line 54
} // library marker zwaveTools.meterTools, line 55

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterSupportedReport cmd, Integer ep = null ) // library marker zwaveTools.meterTools, line 57
{  // library marker zwaveTools.meterTools, line 58

    if ((cmd.meterType as Integer) != 1 ){ // library marker zwaveTools.meterTools, line 60
		log.warn "Device ${device.displayName}: Received a meter support type of ${cmd.meterType} which is not processed by this code. Endpoint ${ep ?: 0}." // library marker zwaveTools.meterTools, line 61
		return null // library marker zwaveTools.meterTools, line 62
	} // library marker zwaveTools.meterTools, line 63
	List<Integer> scaleList = getMeterScalesAsList(cmd) // library marker zwaveTools.meterTools, line 64

	getThisEndpointData(ep ?: 0).put("metersSupported", scaleList) // Store in the device record so you don't have to ask device again! // library marker zwaveTools.meterTools, line 66
	meterTools_refreshScales(scaleList, ep) // library marker zwaveTools.meterTools, line 67
} // library marker zwaveTools.meterTools, line 68

Map getFormattedZWaveMeterReportEvent(def cmd) // library marker zwaveTools.meterTools, line 70
{ // library marker zwaveTools.meterTools, line 71
	BigDecimal meterValue = cmd.scaledMeterValue // library marker zwaveTools.meterTools, line 72
	Integer deltaTime = cmd.deltaTime // library marker zwaveTools.meterTools, line 73

	Map meterReport = [ // library marker zwaveTools.meterTools, line 75
		1:[ // library marker zwaveTools.meterTools, line 76
			0000:[name: "energy", 		value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Active Power Usage"], // library marker zwaveTools.meterTools, line 77
			1000:[name: "energyConsumed", 	value: meterValue, deltaTime:deltaTime, unit: "kVAh", descriptionText:"Energy Consumed"], // custom attribute energyConsumed must be added to driver preferences // library marker zwaveTools.meterTools, line 78
			2000:[name: "power", 		value: meterValue, deltaTime:deltaTime, unit: "W", descriptionText:"Watts"], // library marker zwaveTools.meterTools, line 79
			3000:[name: "pulseCount", 	value: meterValue, deltaTime:deltaTime, unit: "Pulses", descriptionText:"Electric Meter - Pulse Count"], // custom attribute must be added to driver preferences // library marker zwaveTools.meterTools, line 80
			4000:[name: "voltage", 		value: meterValue, deltaTime:deltaTime, unit: "V", descriptionText:"Current Voltage"], // library marker zwaveTools.meterTools, line 81
			5000:[name: "amperage", 	value: meterValue, deltaTime:deltaTime, unit: "A", descriptionText:"Current Amperage"], // library marker zwaveTools.meterTools, line 82
			6000:[name: "powerFactor", 	value: meterValue, deltaTime:deltaTime, unit: "Power Factor", descriptionText:"Power Factor"], // custom attribute must be added to driver preferences // library marker zwaveTools.meterTools, line 83
			7000:[name: "reactiveCurrent", 	value: meterValue, deltaTime:deltaTime, unit: "KVar", descriptionText:"Reactive Current"], // custom attribute must be added to driver preferences // library marker zwaveTools.meterTools, line 84
			7001:[name: "reactivePower", 	value: meterValue, deltaTime:deltaTime, unit: "KVarh", descriptionText:"Reactive Power"], // custom attribute must be added to driver preferences // library marker zwaveTools.meterTools, line 85
		],  // library marker zwaveTools.meterTools, line 86
		2:[ // Gas meter // library marker zwaveTools.meterTools, line 87
			0000:[name: "gasFlow", 	value: meterValue, deltaTime:deltaTime, unit: "m3", descriptionText:"Gas volume."], // library marker zwaveTools.meterTools, line 88
			1000:[name: "gasFlow", 	value: meterValue, deltaTime:deltaTime, unit: "ft3", descriptionText:"Gas volume"], // library marker zwaveTools.meterTools, line 89
			3000:[name: "gasPulses", 	value: meterValue, deltaTime:deltaTime, unit: "pulses", descriptionText:"Gas MEter - Pulse Count"], // library marker zwaveTools.meterTools, line 90
		], // library marker zwaveTools.meterTools, line 91
		3:[ // Water meter // library marker zwaveTools.meterTools, line 92
			0000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "m3", descriptionText:"Water Volume"], // library marker zwaveTools.meterTools, line 93
			1000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "ft3", descriptionText:"Water Volume"], // library marker zwaveTools.meterTools, line 94
			2000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "gpm", descriptionText:"Water flow"],			 // library marker zwaveTools.meterTools, line 95
			3000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "pulses", descriptionText:"Water Meter - Pulse Count"], // library marker zwaveTools.meterTools, line 96
		], // library marker zwaveTools.meterTools, line 97
		4:[ //Heating meter // library marker zwaveTools.meterTools, line 98
			0000:[name: "heatingMeter", 	value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Heating"], // library marker zwaveTools.meterTools, line 99
		], // library marker zwaveTools.meterTools, line 100
		5:[ // Cooling meter // library marker zwaveTools.meterTools, line 101
			0000:[name: "coolingMeter", 	value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Cooling"], // library marker zwaveTools.meterTools, line 102
		] // library marker zwaveTools.meterTools, line 103
	].get(cmd.meterType as Integer)?.get( ( (cmd.scale as Integer) * 1000 + ((cmd.scale2 ?: 0) as Integer))) // library marker zwaveTools.meterTools, line 104

	if (meterReport.is( null )) return null // library marker zwaveTools.meterTools, line 106

	if (cmd.scaledPreviousMeterValue)  // library marker zwaveTools.meterTools, line 108
		{ // library marker zwaveTools.meterTools, line 109
			String reportText = meterReport.descriptionText + " Prior value: ${cmd.scaledPreviousMeterValue}" // library marker zwaveTools.meterTools, line 110
			meterReport += [previousValue:(cmd.scaledPreviousMeterValue), descriptionText:reportText] // library marker zwaveTools.meterTools, line 111
		} // library marker zwaveTools.meterTools, line 112

	return meterReport // library marker zwaveTools.meterTools, line 114
} // library marker zwaveTools.meterTools, line 115

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterReport cmd, Integer ep = null ) // library marker zwaveTools.meterTools, line 117
{ // library marker zwaveTools.meterTools, line 118
	List<Map> events = [] // library marker zwaveTools.meterTools, line 119
	events << getFormattedZWaveMeterReportEvent(cmd) // library marker zwaveTools.meterTools, line 120
	List<com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(ep) // library marker zwaveTools.meterTools, line 121
	if ((ep ?: 0) == 0) { targetDevices += this } // library marker zwaveTools.meterTools, line 122
	targetDevices.each{ it.parse( events ) } // library marker zwaveTools.meterTools, line 123

} // library marker zwaveTools.meterTools, line 125

// ~~~~~ end include (67) zwaveTools.meterTools ~~~~~

// ~~~~~ start include (69) zwaveTools.sensorTools ~~~~~
library ( // library marker zwaveTools.sensorTools, line 1
        base: "driver", // library marker zwaveTools.sensorTools, line 2
        author: "jvm33", // library marker zwaveTools.sensorTools, line 3
        category: "zwave", // library marker zwaveTools.sensorTools, line 4
        description: "Handle Binary and MultiLevel Sensors", // library marker zwaveTools.sensorTools, line 5
        name: "sensorTools", // library marker zwaveTools.sensorTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.sensorTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.sensorTools, line 8
		version: "0.0.1", // library marker zwaveTools.sensorTools, line 9
		dependencies: "zwaveTools.endpointTools", // library marker zwaveTools.sensorTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/sensorTools.groovy" // library marker zwaveTools.sensorTools, line 11
) // library marker zwaveTools.sensorTools, line 12

////////////////////////////////////////////////////////////////////// // library marker zwaveTools.sensorTools, line 14
//////        Handle   Binary Sensor     /////// // library marker zwaveTools.sensorTools, line 15
////////////////////////////////////////////////////////////////////// // library marker zwaveTools.sensorTools, line 16
void sensorTools_refresh(Integer ep = null ) { // library marker zwaveTools.sensorTools, line 17
	binarySensorRefresh(ep) // library marker zwaveTools.sensorTools, line 18
	multilevelSensorRefresh(ep) // library marker zwaveTools.sensorTools, line 19

} // library marker zwaveTools.sensorTools, line 21
void	binarySensorRefresh(Integer ep = null ) { // library marker zwaveTools.sensorTools, line 22
	log.warn "Device ${device}: binarySensorRefresh function is not implemented." // library marker zwaveTools.sensorTools, line 23
} // library marker zwaveTools.sensorTools, line 24

void multilevelSensorRefresh(Integer ep =  null ) { // library marker zwaveTools.sensorTools, line 26
	log.warn "Device ${device}: multilevelSensorRefresh function is not implemented." // library marker zwaveTools.sensorTools, line 27
} // library marker zwaveTools.sensorTools, line 28

Map getFormattedZWaveSensorBinaryEvent(def cmd) // library marker zwaveTools.sensorTools, line 30
{ // library marker zwaveTools.sensorTools, line 31
	Map returnEvent = [ 	 // library marker zwaveTools.sensorTools, line 32
			2:[ // Smoke // library marker zwaveTools.sensorTools, line 33
				0:[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."], // library marker zwaveTools.sensorTools, line 34
				255:[name:"smoke" , value:"detected", descriptionText:"Smoke detected."],  // library marker zwaveTools.sensorTools, line 35
				], // library marker zwaveTools.sensorTools, line 36
			3:[ // CO // library marker zwaveTools.sensorTools, line 37
				0:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."], // library marker zwaveTools.sensorTools, line 38
				255:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."], // library marker zwaveTools.sensorTools, line 39
				], // library marker zwaveTools.sensorTools, line 40
			4:[ // CO2 // library marker zwaveTools.sensorTools, line 41
				0:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],	 // library marker zwaveTools.sensorTools, line 42
				255:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."], // library marker zwaveTools.sensorTools, line 43
				],					 // library marker zwaveTools.sensorTools, line 44
			6:[ // Water // library marker zwaveTools.sensorTools, line 45
				0:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."], // library marker zwaveTools.sensorTools, line 46
				255:[name:"water" , value:"wet", descriptionText:"Water leak detected."], // library marker zwaveTools.sensorTools, line 47
				], // library marker zwaveTools.sensorTools, line 48
			8:[ // Tamper // library marker zwaveTools.sensorTools, line 49
				0:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."], // library marker zwaveTools.sensorTools, line 50
				255:[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"],  // library marker zwaveTools.sensorTools, line 51
				], // library marker zwaveTools.sensorTools, line 52
			10:[ // Door/Window // library marker zwaveTools.sensorTools, line 53
				0:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed"], 					 // library marker zwaveTools.sensorTools, line 54
				255:[name:"contact" , value:"open", descriptionText:"Contact sensor, open"], 					 // library marker zwaveTools.sensorTools, line 55
				], // library marker zwaveTools.sensorTools, line 56
			12:[ // Motion // library marker zwaveTools.sensorTools, line 57
				0:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."], // library marker zwaveTools.sensorTools, line 58
				255:[name:"motion" , value:"active", descriptionText:"Motion detected."], // library marker zwaveTools.sensorTools, line 59
				] // library marker zwaveTools.sensorTools, line 60

		].get(cmd.sensorType as Integer)?.get(cmd.sensorValue as Integer) // library marker zwaveTools.sensorTools, line 62

		return returnEvent // library marker zwaveTools.sensorTools, line 64
} // library marker zwaveTools.sensorTools, line 65

void zwaveEvent(hubitat.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd, Integer ep = null ) // library marker zwaveTools.sensorTools, line 67
{ // library marker zwaveTools.sensorTools, line 68
	Map thisEvent = getFormattedZWaveSensorBinaryEvent(cmd) // library marker zwaveTools.sensorTools, line 69
	sendEventToEndpoints(event:thisEvent, ep:ep) // library marker zwaveTools.sensorTools, line 70
} // library marker zwaveTools.sensorTools, line 71

////////////////////////////////////////////////////////////////////// // library marker zwaveTools.sensorTools, line 73
//////        Handle  Multilevel Sensor       /////// // library marker zwaveTools.sensorTools, line 74
////////////////////////////////////////////////////////////////////// // library marker zwaveTools.sensorTools, line 75
Map getFormattedZWaveSensorMultilevelReportEvent(def cmd) // library marker zwaveTools.sensorTools, line 76
{ // library marker zwaveTools.sensorTools, line 77
	Map tempReport = [ // library marker zwaveTools.sensorTools, line 78
		1: [name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Air temperature"],  // library marker zwaveTools.sensorTools, line 79
		23:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Water temperature"],  // library marker zwaveTools.sensorTools, line 80
		24:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Soil temperature"],  // library marker zwaveTools.sensorTools, line 81
		34:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Target temperature"],  // library marker zwaveTools.sensorTools, line 82
		62:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Boiler Water temperature"], // library marker zwaveTools.sensorTools, line 83
		63:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Domestic Hot Water temperature"],  // library marker zwaveTools.sensorTools, line 84
		64:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Outside temperature"],  // library marker zwaveTools.sensorTools, line 85
		65:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Exhaust temperature"], // library marker zwaveTools.sensorTools, line 86
		72:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Return Air temperature"],		 // library marker zwaveTools.sensorTools, line 87
		73:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Supply Air temperature"],		 // library marker zwaveTools.sensorTools, line 88
		74:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Condenser Coil temperature"],		 // library marker zwaveTools.sensorTools, line 89
		75:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Evaporator Coil temperature"],		 // library marker zwaveTools.sensorTools, line 90
		76:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Liquid Line temperature"], // library marker zwaveTools.sensorTools, line 91
		77:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Discharge Line temperature"], // library marker zwaveTools.sensorTools, line 92
		80:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Defrost temperature"],	 // library marker zwaveTools.sensorTools, line 93
	].get(cmd.sensorType as Integer) // library marker zwaveTools.sensorTools, line 94

	if (tempReport) { // library marker zwaveTools.sensorTools, line 96
		tempReport.value = convertTemperatureIfNeeded(cmd.scaledMeterValue, (((cmd.scale as Integer) == 0) ? "C" : "F"), 2) // library marker zwaveTools.sensorTools, line 97
		return tempReport + [deviceType:"ZWV", zwaveOriginalMessage:cmd.format()] // library marker zwaveTools.sensorTools, line 98
	} // library marker zwaveTools.sensorTools, line 99

	Map otherSensorReport = [ // library marker zwaveTools.sensorTools, line 101
		3000:[name: "illuminance", value: cmd.scaledMeterValue, unit: "%"], // Illuminance // library marker zwaveTools.sensorTools, line 102
		3001:[name: "illuminance", value: cmd.scaledMeterValue, unit: "lx"], // library marker zwaveTools.sensorTools, line 103
		4000:[name: "power", value: cmd.scaledMeterValue, unit: "W"], // library marker zwaveTools.sensorTools, line 104
		4001:[name: "power", value: cmd.scaledMeterValue, unit: "BTU/h"], // library marker zwaveTools.sensorTools, line 105
		5000:[name: "humidity", value: cmd.scaledMeterValue, unit: "%"], // library marker zwaveTools.sensorTools, line 106
		5001:[name: "humidity", value: cmd.scaledMeterValue, unit: "g/m3"], // library marker zwaveTools.sensorTools, line 107
		8000:[name: "pressure", value: (cmd.scaledMeterValue * ((cmd.scale == 0) ? 1000 : 3386.38867)), unit:"Pa", descriptionText:"Atmospheric Pressure"], // library marker zwaveTools.sensorTools, line 108
		9000:[name: "pressure", value: (cmd.scaledMeterValue * ((cmd.scale == 0) ? 1000 : 3386.38867)), unit:"Pa", descriptionText:"Barometric Pressure"], // library marker zwaveTools.sensorTools, line 109
		15000:[name: "voltage", value: cmd.scaledMeterValue, unit: "V"], // library marker zwaveTools.sensorTools, line 110
		15001:[name: "voltage", value: cmd.scaledMeterValue, unit: "mV"], // library marker zwaveTools.sensorTools, line 111
		16000:[name: "amperage", value: cmd.scaledMeterValue, unit: "A"], // library marker zwaveTools.sensorTools, line 112
		16001:[name: "amperage", value: cmd.scaledMeterValue, unit: "mA"], // library marker zwaveTools.sensorTools, line 113
		17000:[name: "carbonDioxide ", value: cmd.scaledMeterValue, unit: "ppm"], // library marker zwaveTools.sensorTools, line 114
		27000:[name: "ultravioletIndex", value: cmd.scaledMeterValue, unit: "UV Index"], // library marker zwaveTools.sensorTools, line 115
		40000:[name: "carbonMonoxide ", value: cmd.scaledMeterValue, unit: "ppm"], // library marker zwaveTools.sensorTools, line 116
		56000:[name: "rate", value: cmd.scaledMeterValue, unit: "LPH"], // Water flow	 // library marker zwaveTools.sensorTools, line 117
		58000:[name: "rssi", value: cmd.scaledMeterValue, unit: "%"], // library marker zwaveTools.sensorTools, line 118
		58001:[name: "rssi", value: cmd.scaledMeterValue, unit: "dBm"], // library marker zwaveTools.sensorTools, line 119
		67000:[name: "pH", value: cmd.scaledMeterValue, unit: "pH"], // library marker zwaveTools.sensorTools, line 120
	].get((cmd.sensorType * 1000 + cmd.scale) as Integer)	 // library marker zwaveTools.sensorTools, line 121

	return otherSensorReport // library marker zwaveTools.sensorTools, line 123
} // library marker zwaveTools.sensorTools, line 124

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv11.SensorMultilevelReport cmd, Integer ep = null ) // library marker zwaveTools.sensorTools, line 126
{ // library marker zwaveTools.sensorTools, line 127
	Map thisEvent = getFormattedZWaveSensorMultilevelReportEvent(cmd) // library marker zwaveTools.sensorTools, line 128
	sendEventToEndpoints(event:thisEvent, ep:ep) // library marker zwaveTools.sensorTools, line 129
} // library marker zwaveTools.sensorTools, line 130

// ~~~~~ end include (69) zwaveTools.sensorTools ~~~~~

// ~~~~~ start include (68) zwaveTools.binaryAndMultilevelDeviceTools ~~~~~
library ( // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 1
        base: "driver", // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 2
        author: "jvm33", // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 3
        category: "zwave", // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 4
        description: "Handles Events for Switches, Dimmers, Fans, Window Shades ", // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 5
        name: "binaryAndMultilevelDeviceTools", // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 8
		version: "0.0.1", // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 9
		dependencies: "zwaveTools.sendReceiveTools, zwaveTools.endpointTools", // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/binaryAndMultiLevelDeviceTools.groovy" // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 11
) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 12
////    Send Simple Z-Wave Commands to Device  ////	 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 13

void sendInitialCommand() { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 15
	// If a device uses 'Supervision', then following a restart, code doesn't know the last sessionID that was sent to  // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 16
	// the device, so to reset that, send a command twice at startup. // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 17
	if (device.hasAttribute("switch") && (device.currentValue("switch") == "off")) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 18
		sendZwaveValue(value:0) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 19
		sendZwaveValue(value:0) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 20
	} else if ( device.hasAttribute("switch") && (device.currentValue("switch") == "on")) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 21
		if (device.hasAttribute("level")) {  // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 22
			sendZwaveValue(value:(device.currentValue("level") as Integer )) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 23
			sendZwaveValue(value:(device.currentValue("level") as Integer )) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 24
		} else { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 25
			sendZwaveValue(value:99) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 26
			sendZwaveValue(value:99) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 27
		} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 28
	} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 29
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 30

void binaryAndMultiLevelDeviceTools_refresh() { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 32
		if (record.classes.contains(0x25)) 		advancedZwaveSend(zwave.switchBinaryV1.switchBinaryGet(), ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 33
		if (record.classes.contains(0x26)) 		advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelGet(), ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 34
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 35

////    Send Simple Z-Wave Commands to Device  ////	 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 37
void sendZwaveValue(Map params = [value: null , duration: null , ep: null ] ) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 38
{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 39
	Integer newValue = Math.max(Math.min(params.value, 99),0) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 40
	List<Integer> supportedClasses = getThisEndpointClasses(ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 41

	if (supportedClasses.contains(0x26)) { // Multilevel  type device // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 43
		if (! params.duration.is( null) ) advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelSet(value: newValue, dimmingDuration:params.duration), params.ep)	 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 44
			else advancedZwaveSend(zwave.switchMultilevelV1.switchMultilevelSet(value: newValue), params.ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 45
	} else if (supportedClasses.contains(0x25)) { // Switch Binary Type device // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 46
		advancedZwaveSend(zwave.switchBinaryV1.switchBinarySet(switchValue: newValue ), params.ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 47
	} else if (supportedClasses.contains(0x20)) { // Basic Set Type device // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 48
		log.warn "Device ${targetDevice.displayName}: Using Basic Set to turn on device. A more specific command class should be used!" // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 49
		advancedZwaveSend(zwave.basicV1.basicSet(value: newValue ), params.ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 50
	} else { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 51
		log.error "Device ${device.displayName}: Error in function sendZwaveValue(). Device does not implement a supported class to control the device!.${ep ? " Endpoint # is: ${params.ep}." : ""}" // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 52
		return // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 53
	} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 54
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 55

void zwaveEvent(hubitat.zwave.commands.switchbinaryv2.SwitchBinaryReport cmd, ep = null ) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 57
{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 58
	String newSwitchState = ((cmd.value > 0) ? "on" : "off") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 59
	Map switchEvent = [name: "switch", value: newSwitchState, descriptionText: "Switch set", type: "physical"] // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 60

	List <com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(ep ?: 0)?.findAll{it.hasAttribute("switch")} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 62
	if (((ep ?: 0 )== 0) && device.hasAttribute ("switch")) targetDevices += device // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 63

	targetDevices.each { it.sendEvent(switchEvent) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 65
		if (txtEnable) log.info "Device ${it.displayName} set to ${newSwitchState}." // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 66
	} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 67

	if (targetDevices.size() < 1) log.error "Device ${device.displayName}: received a Switch Binary Report for a device that does not have a switch attribute. Endpoint ${ep ?: 0}." // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 69
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 70

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, ep = null ) { processSwitchReport(cmd, ep) } // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 72
void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd, ep = null ) { processSwitchReport(cmd, ep) } // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 73
void processSwitchReport(cmd, ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 74
{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 75

	List<com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 77
	if ((ep ?: 0 )== 0) targetDevices += device // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 78

		Integer targetLevel = ((Integer) cmd.value == 99) ? 100 : cmd.value // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 80
		if (cmd.hasProperty("targetValue") && cmd.targetValue && (cmd.duration > 0 ) ) {  // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 81
				targetLevel = ((Integer)  cmd.targetValue == 99) ? 100 : cmd.targetValue // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 82
			}     // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 83
	targetDevices.each{ it -> // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 84
		if (it.hasAttribute("position"))  {  // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 85
			it.sendEvent( name: "position", value: targetLevel , unit: "%", descriptionText: "Position set to ${targetLevel}%", type: "physical" ) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 86
		} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 87
		if (it.hasAttribute("windowShade")) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 88
			String positionDescription = [0:"closed", 99:"open", 100:"open"].get(targetLevel, "partially open") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 89
			it.sendEvent( name: "windowShade", value: positionDescription, descriptionText: "Window Shade position set to ${positionDescription}.", type: "physical" )	 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 90
		} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 91

		if (it.hasAttribute("level") || it.hasAttribute("switch") ) // Switch or a fan // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 93
		{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 94
			String newSwitchState = ((targetLevel != 0) ? "on" : "off") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 95

			if (it.hasAttribute("switch")) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 97
				it.sendEvent(	name: "switch", value: newSwitchState, descriptionText: "Switch state set to ${newSwitchState}", type: "physical" ) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 98
				if (txtEnable) log.info "Device ${it.displayName} set to ${newSwitchState}." // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 99
			} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 100
			if (it.hasAttribute("speed")) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 101
				it.sendEvent( name: "speed", value: levelToSpeed(targetLevel), descriptionText: "Speed set", type: "physical" ) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 102
			} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 103
			if (it.hasAttribute("level") && (targetLevel != 0 )) // Only handle on values 1-99 here. If device was turned off, that would be handle in the switch state block above. // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 104
			{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 105
				it.sendEvent( name: "level", value: targetLevel, descriptionText: "Level set to ${targetLevel}%.", unit:"%", type: "physical" ) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 106
				if (txtEnable) log.info "Device ${it.displayName} level set to ${targetLevel}%"		 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 107
			} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 108
		} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 109
	} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 110
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 111

void componentOn(com.hubitat.app.DeviceWrapper cd){ on(cd:cd) } // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 113

void on(Map params = [cd: null , duration: null , level: null ]) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 115
{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 116
	Integer ep = getChildEndpointNumber(params.cd) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 117

	sendEventToEndpoints(event:[name: "switch", value: "on", descriptionText: "Device turned on", type: "digital"] , ep:ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 119

	Integer targetLevel = 100 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 121
	if (params.level) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 122
		targetLevel = (params.level as Integer)  // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 123
	} else { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 124
		List<com.hubitat.app.DeviceWrapper> targets = getChildDeviceListByEndpoint(ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 125
		if (ep == 0) targets += device // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 126
		targetLevel = (targets?.find{it.hasAttribute("level")}?.currentValue("level") as Integer) ?: 100 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 127
	} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 128
	targetLevel = Math.min(Math.max(targetLevel, 1), 100) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 129

	sendEventToEndpoints(event:[name: "level", value: targetLevel, descriptionText: "Device level set", unit:"%", type: "digital"], ep:ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 131

	sendZwaveValue(value: targetLevel, duration: params.duration, ep: ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 133
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 134

void componentOff(com.hubitat.app.DeviceWrapper cd){ 	off(cd:cd) } // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 136

void off(Map params = [cd: null , duration: null ]) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 138
	Integer ep = getChildEndpointNumber(params.cd) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 139
	sendEventToEndpoints(event:[name: "switch", value: "off", descriptionText: "Device turned off", type: "digital"], ep:ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 140
	sendZwaveValue(value: 0, duration: params.duration, ep: ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 141
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 142

void componentSetLevel(com.hubitat.app.DeviceWrapper cd, level, transitionTime = null) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 144
	if (cd.hasCapability("FanControl") ) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 145
			setSpeed(cd:cd, level:level, speed:levelToSpeed(level as Integer)) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 146
		} else {  // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 147
			setLevel(level:level, duration:transitionTime, cd:cd)  // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 148
		} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 149
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 150

void setLevel(level, duration = null ) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 152
	setLevel(level:level, duration:duration) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 153
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 154

void setLevel(Map params = [cd: null , level: null , duration: null ]) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 156
{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 157
	if ( (params.level as Integer) <= 0) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 158
		off(cd:params.cd, duration:params.duration) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 159
	} else { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 160
		on(cd:params.cd, level:params.level, duration:params.duration) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 161
	} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 162
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 163


void componentStartLevelChange(com.hubitat.app.DeviceWrapper cd, direction) { startLevelChange(direction, cd) } // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 166
void startLevelChange(direction, cd = null ){ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 167
	com.hubitat.app.DeviceWrapper targetDevice = (cd ? cd : device) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 168
	Integer ep = cd ? (cd.deviceNetworkId.split("-ep")[-1] as Integer) : null // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 169

    Integer upDown = (direction == "down") ? 1 : 0 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 171

	def sendMe = zwave.switchMultilevelV1.switchMultilevelStartLevelChange(upDown: upDown, ignoreStartLevel: 1, startLevel: 0) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 173

    advancedZwaveSend(sendMe, ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 175
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 176



void componentStopLevelChange(com.hubitat.app.DeviceWrapper cd) { stopLevelChange(cd) } // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 180
void stopLevelChange(cd = null ){ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 181
	com.hubitat.app.DeviceWrapper targetDevice = (cd ? cd : device) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 182
	Integer ep = cd ? (cd.deviceNetworkId.split("-ep")[-1] as Integer) : null // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 183

	advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelStopLevelChange(), ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 185
	advancedZwaveSend(zwave.basicV1.basicGet(), ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 186
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 187

//////////////////////////////////////////////////////// // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 189
//////                Handle Fans                /////// // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 190
//////////////////////////////////////////////////////// // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 191

String levelToSpeed(Integer level) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 193
{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 194
// 	Map speeds = [(0..0):"off", (1..20):"low", (21..40):"medium-low", (41-60):"medium", (61..80):"medium-high", (81..100):"high"] // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 195
//	return (speeds.find{ key, value -> key.contains(level) }).value // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 196
	switch (level) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 197
	{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 198
	case 0 : 		return "off" ; break // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 199
	case 1..20: 	return "low" ; break // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 200
	case 21..40: 	return "medium-low" ; break // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 201
	case 41..60: 	return "medium" ; break // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 202
	case 61..80: 	return "medium-high" ; break // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 203
	case 81..100: 	return "high" ; break // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 204
	default : return null // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 205
	} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 206
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 207

Integer speedToLevel(String speed) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 209
	return ["off": 0, "low":20, "medium-low":40, "medium":60, "medium-high":80, "high":100].get(speed) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 210
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 211

void componentSetSpeed(com.hubitat.app.DeviceWrapper cd, speed) { setSpeed(speed:speed, cd:cd) } // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 213
void setSpeed(speed, com.hubitat.app.DeviceWrapper cd = null ) { setSpeed(speed:speed, cd:cd) } // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 214
void setSpeed(Map params = [speed: null , level: null , cd: null ]) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 215
{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 216
	com.hubitat.app.DeviceWrapper originatingDevice = params.cd ?: device // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 217
	Integer ep = params.cd ? (originatingDevice.deviceNetworkId.split("-ep")[-1] as Integer) : 0 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 218

	List<com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 220

	if (params.speed.is( null ) ) { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 222
		log.error "Device ${originatingDevice.displayName}: setSpeed command received without a valid speed setting. Speed setting was ${params.speed}. Returning without doing anything!" // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 223
		return // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 224
	} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 225

    if (logEnable) log.info "Device ${device.displayName}: received setSpeed(${params.speed}) request from child ${originatingDevice.displayName}" // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 227

	String currentOnState = originatingDevice.currentValue("switch") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 229
	Integer currentLevel = originatingDevice.currentValue("level") // Null if attribute isn't supported. // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 230
	Integer targetLevel // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 231

	if (params.speed == "on") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 233
	{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 234
		currentLevel = currentLevel ?: 100 // If it was a a level of 0, turn to 100%. Level should never be 0 -- except it might be 0 or null on first startup! // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 235

		targetDevices.each{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 237
			it.sendEvent(name: "switch", value: "on", descriptionText: "Fan turned on", type: "digital") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 238
			it.sendEvent(name: "level", value: currentLevel, descriptionText: "Fan level set", unit:"%", type: "digital") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 239
			it.sendEvent(name: "speed", value: levelToSpeed(currentLevel), descriptionText: "Fan speed set", type: "digital") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 240
		} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 241
		sendZwaveValue(value: currentLevel, duration: 0, ep: ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 242

	} else if (params.speed == "off") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 244
	{  // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 245
		targetDevices.each{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 246
			it.sendEvent(name: "switch", value: "off", descriptionText: "Fan switched off", type: "digital") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 247
			it.sendEvent(name: "speed", value: "off", descriptionText: "Fan speed set", type: "digital") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 248
		}			 // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 249

		sendZwaveValue(value: 0, duration: 0, ep: ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 251

	} else { // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 253
		targetLevel = (params.level as Integer) ?: speedToLevel(params.speed) ?: currentLevel // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 254
		targetDevices.each{ // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 255
			it.sendEvent(name: "switch", value: "on", descriptionText: "Fan turned on", type: "digital") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 256
			it.sendEvent(name: "speed", value: params.speed, descriptionText: "Fan speed set", type: "digital") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 257
			it.sendEvent(name: "level", value: targetLevel, descriptionText: "Fan level set", unit:"%", type: "digital") // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 258
		} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 259
		sendZwaveValue(value: targetLevel, duration: 0, ep: ep) // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 260
	} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 261
} // library marker zwaveTools.binaryAndMultilevelDeviceTools, line 262

// ~~~~~ end include (68) zwaveTools.binaryAndMultilevelDeviceTools ~~~~~

// ~~~~~ start include (100) zwaveTools.centralSceneTools ~~~~~
library ( // library marker zwaveTools.centralSceneTools, line 1
        base: "driver", // library marker zwaveTools.centralSceneTools, line 2
        author: "jvm33", // library marker zwaveTools.centralSceneTools, line 3
        category: "zwave", // library marker zwaveTools.centralSceneTools, line 4
        description: "Handles Zwave Central Scene reports", // library marker zwaveTools.centralSceneTools, line 5
        name: "centralSceneTools", // library marker zwaveTools.centralSceneTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.centralSceneTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.centralSceneTools, line 8
		version: "0.0.1", // library marker zwaveTools.centralSceneTools, line 9
		dependencies: "zwaveTools.sendReceiveTools", // library marker zwaveTools.centralSceneTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/centralSceneTools.groovy" // library marker zwaveTools.centralSceneTools, line 11
) // library marker zwaveTools.centralSceneTools, line 12
import java.util.concurrent.*  // library marker zwaveTools.centralSceneTools, line 13
import groovy.transform.Field // library marker zwaveTools.centralSceneTools, line 14

/////////////////////////////////////////////////////////////////////////////////////////////// // library marker zwaveTools.centralSceneTools, line 16
///////////////                  Central Scene Processing          //////////////////////////// // library marker zwaveTools.centralSceneTools, line 17
/////////////////////////////////////////////////////////////////////////////////////////////// // library marker zwaveTools.centralSceneTools, line 18

// Use a concurrentHashMap to hold the last reported state. This is used for "held" state checking // library marker zwaveTools.centralSceneTools, line 20
// In a "held" state, the device will send "held down refresh" messages at either 200 mSecond or 55 second intervals. // library marker zwaveTools.centralSceneTools, line 21
// Hubitat should not generated repreated "held" messages in response to a refresh, so inhibit those // library marker zwaveTools.centralSceneTools, line 22
// Since the concurrentHashMap is @Field static -- its data structure is common to all devices using this // library marker zwaveTools.centralSceneTools, line 23
// Driver, therefore you have to key it using the device.deviceNetworkId to get the value for a particuarl device. // library marker zwaveTools.centralSceneTools, line 24
@Field static  ConcurrentHashMap centralSceneButtonState = new ConcurrentHashMap<String, String>(64, 0.75, 1) // library marker zwaveTools.centralSceneTools, line 25

void centralSceneTools_initialize(){ // library marker zwaveTools.centralSceneTools, line 27
	advancedZwaveSend(zwave.centralSceneV3.centralSceneSupportedGet()) // library marker zwaveTools.centralSceneTools, line 28
} // library marker zwaveTools.centralSceneTools, line 29
String getCentralSceneButtonState(Integer button) {  // library marker zwaveTools.centralSceneTools, line 30
 	String key = "${device.deviceNetworkId}.Button.${button}" // library marker zwaveTools.centralSceneTools, line 31
	return centralSceneButtonState.get(key) // library marker zwaveTools.centralSceneTools, line 32
} // library marker zwaveTools.centralSceneTools, line 33

String setCentralSceneButtonState(Integer button, String state) { // library marker zwaveTools.centralSceneTools, line 35
 	String key = "${device.deviceNetworkId}.Button.${button}" // library marker zwaveTools.centralSceneTools, line 36
	centralSceneButtonState.put(key, state) // library marker zwaveTools.centralSceneTools, line 37
	return centralSceneButtonState.get(key) // library marker zwaveTools.centralSceneTools, line 38
} // library marker zwaveTools.centralSceneTools, line 39

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneSupportedReport  cmd) { // library marker zwaveTools.centralSceneTools, line 41
	List<Map> events = [] // library marker zwaveTools.centralSceneTools, line 42
	events << [name:"numberOfButtons", value: cmd.supportedScenes, descriptionText:"Reporting Number of Buttons:${cmd.supportedScenes}."] // library marker zwaveTools.centralSceneTools, line 43
	(childDevices + this).each{ it.parse(events) } // library marker zwaveTools.centralSceneTools, line 44
} // library marker zwaveTools.centralSceneTools, line 45

void doubleTap(button) 	{ multiTap(button, 2)	} // library marker zwaveTools.centralSceneTools, line 47
void push(button) 		{ multiTap(button, 1) } // library marker zwaveTools.centralSceneTools, line 48
void hold(button) 		{  // library marker zwaveTools.centralSceneTools, line 49
		List<Map> events = [] // library marker zwaveTools.centralSceneTools, line 50
		events <<	[name:"held", value:button, type:"digital", isStateChange: true , descriptionText:"Holding button ${button}." ] // library marker zwaveTools.centralSceneTools, line 51
		(childDevices + this).each{ it.parse(events) } // library marker zwaveTools.centralSceneTools, line 52
	} // library marker zwaveTools.centralSceneTools, line 53
void release(button) 	{  // library marker zwaveTools.centralSceneTools, line 54
		List<Map> events  = [] // library marker zwaveTools.centralSceneTools, line 55
		events << [name:"released", value:button, type:"digital", isStateChange: true , descriptionText:"Released button ${button}." ] // library marker zwaveTools.centralSceneTools, line 56
		(childDevices + this).each{ it.parse(events) } // library marker zwaveTools.centralSceneTools, line 57
	} // library marker zwaveTools.centralSceneTools, line 58

void multiTap(button, taps) { // library marker zwaveTools.centralSceneTools, line 60
	List<Map> events = [] // library marker zwaveTools.centralSceneTools, line 61

	if (taps == 1) { // library marker zwaveTools.centralSceneTools, line 63
	    events << [name:"pushed", value:button, type:"digital", isStateChange: true , descriptionText:"Button ${button} pushed." ] // library marker zwaveTools.centralSceneTools, line 64
	} else if (taps == 2) { // library marker zwaveTools.centralSceneTools, line 65
		events << [name:"doubleTapped", value:button, type:"digital", isStateChange: true , descriptionText:"Button ${button} double-tapped."] // library marker zwaveTools.centralSceneTools, line 66
	} // library marker zwaveTools.centralSceneTools, line 67

    events << [[name:"multiTapButton", value:("${button}.${taps}" as Float), type:"physical", unit:"Button #.Tap Count", isStateChange: true , descriptionText:"Button ${button} tapped ${taps} times." ]] // library marker zwaveTools.centralSceneTools, line 69
	(childDevices + this).each{ it.parse(events) } // library marker zwaveTools.centralSceneTools, line 70
} // library marker zwaveTools.centralSceneTools, line 71

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd) // library marker zwaveTools.centralSceneTools, line 73
{ // library marker zwaveTools.centralSceneTools, line 74
	List<Map> events = [] // library marker zwaveTools.centralSceneTools, line 75

	// Check if central scene is already in a held state, if so, and you get another held message, its a refresh, so don't send a sendEvent // library marker zwaveTools.centralSceneTools, line 77
	if ((getCentralSceneButtonState((Integer) cmd.sceneNumber) == "held") && (((Integer) cmd.keyAttributes) == 2)) return // library marker zwaveTools.centralSceneTools, line 78

	// Central scene events should be sent with isStateChange:true since it is valid to send two of the same events in a row (except held, whcih is handled in previous line) // library marker zwaveTools.centralSceneTools, line 80
    Map basicCCEvent = [value:cmd.sceneNumber, type:"physical", unit:"button#", isStateChange: true ] // library marker zwaveTools.centralSceneTools, line 81

	basicCCEvent.name = [	0:"pushed", 1:"released", 2:"held",  3:"doubleTapped",  // library marker zwaveTools.centralSceneTools, line 83
					4:"buttonTripleTapped", 5:"buttonFourTaps", 6:"buttonFiveTaps"].get((Integer)cmd.keyAttributes) // library marker zwaveTools.centralSceneTools, line 84

	String tapDescription = [	0:"Pushed", 1:"Released", 2:"Held",  3:"Double-Tapped",  // library marker zwaveTools.centralSceneTools, line 86
								4:"Three Taps", 5:"Four Taps", 6:"Five Taps"].get((Integer)cmd.keyAttributes) // library marker zwaveTools.centralSceneTools, line 87

	// Save the event name for event that is about to be sent using sendEvent. This is important for 'held' state refresh checking // library marker zwaveTools.centralSceneTools, line 89
	setCentralSceneButtonState((Integer) cmd.sceneNumber , basicCCEvent.name)	 // library marker zwaveTools.centralSceneTools, line 90

	basicCCEvent.descriptionText="Button #${cmd.sceneNumber}: ${tapDescription}" // library marker zwaveTools.centralSceneTools, line 92

	events << basicCCEvent // library marker zwaveTools.centralSceneTools, line 94


	// Next code is for the custom attribute "multiTapButton". // library marker zwaveTools.centralSceneTools, line 97
	Integer taps = [0:1, 3:2, 4:3, 5:4, 6:5].get((Integer)cmd.keyAttributes) // library marker zwaveTools.centralSceneTools, line 98
	if ( taps) { // library marker zwaveTools.centralSceneTools, line 99
		events << [ name:"multiTapButton", value:("${cmd.sceneNumber}.${taps}" as Float),  // library marker zwaveTools.centralSceneTools, line 100
					unit:"Button.Taps", type:"physical",  // library marker zwaveTools.centralSceneTools, line 101
					descriptionText:"MultiTapButton ${cmd.sceneNumber}.${taps}", isStateChange: true  ] // library marker zwaveTools.centralSceneTools, line 102
	}  // library marker zwaveTools.centralSceneTools, line 103
	(childDevices + this).each{ it.parse(events) } // library marker zwaveTools.centralSceneTools, line 104
} // library marker zwaveTools.centralSceneTools, line 105

// ~~~~~ end include (100) zwaveTools.centralSceneTools ~~~~~

// ~~~~~ start include (132) zwaveTools.openSmarthouseTools ~~~~~
library ( // library marker zwaveTools.openSmarthouseTools, line 1
        base: "driver", // library marker zwaveTools.openSmarthouseTools, line 2
        author: "jvm33", // library marker zwaveTools.openSmarthouseTools, line 3
        category: "zwave", // library marker zwaveTools.openSmarthouseTools, line 4
        description: "Tools for Interacting with the OpenSmartHouse.org database", // library marker zwaveTools.openSmarthouseTools, line 5
        name: "openSmarthouseTools", // library marker zwaveTools.openSmarthouseTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.openSmarthouseTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.openSmarthouseTools, line 8
		version: "0.0.1", // library marker zwaveTools.openSmarthouseTools, line 9
		dependencies: "", // library marker zwaveTools.openSmarthouseTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/openSmartHouseTools.groovy" // library marker zwaveTools.openSmarthouseTools, line 11
) // library marker zwaveTools.openSmarthouseTools, line 12

Map reparseDeviceData(deviceData = null ) // library marker zwaveTools.openSmarthouseTools, line 14
{ // library marker zwaveTools.openSmarthouseTools, line 15
	// When data is stored in the state.deviceRecord it can lose its original data types, so need to restore after reading the data froms state. // library marker zwaveTools.openSmarthouseTools, line 16
	// This is only done during the startup / initialize routine and results are stored in a global variable, so it is only done for the first device of a particular model. // library marker zwaveTools.openSmarthouseTools, line 17

	if (deviceData.is( null )) return null // library marker zwaveTools.openSmarthouseTools, line 19
	Map reparsed = [formatVersion: null , fingerprints: null , classVersions: null ,endpoints: null , deviceInputs: null ] // library marker zwaveTools.openSmarthouseTools, line 20

	reparsed.formatVersion = deviceData.formatVersion as Integer // library marker zwaveTools.openSmarthouseTools, line 22

	if (deviceData.endpoints) { // library marker zwaveTools.openSmarthouseTools, line 24
		reparsed.endpoints = deviceData.endpoints.collectEntries{k, v -> [(k as Integer), (v)] } // library marker zwaveTools.openSmarthouseTools, line 25
	} else { // library marker zwaveTools.openSmarthouseTools, line 26
		List<Integer> endpoint0Classes = getDataValue("inClusters")?.split(",").collect{ hexStrToUnsignedInt(it) as Integer } // library marker zwaveTools.openSmarthouseTools, line 27
						endpoint0Classes += getDataValue("secureInClusters")?.split(",").collect{ hexStrToUnsignedInt(it) as Integer } // library marker zwaveTools.openSmarthouseTools, line 28
		if (endpoint0Classes.contains(0x60)) // library marker zwaveTools.openSmarthouseTools, line 29
			{ // library marker zwaveTools.openSmarthouseTools, line 30
				log.error "Device ${device.displayName}: Error in function reparseDeviceData. Missing endpoint data for a multi-endpoint device. This usually occurs if there is a locally stored data record which does not properly specify the endpoint data. This device may still function, but only for the root device." // library marker zwaveTools.openSmarthouseTools, line 31
			} // library marker zwaveTools.openSmarthouseTools, line 32
		reparsed.endpoints = [0:[classes:(endpoint0Classes)]] // library marker zwaveTools.openSmarthouseTools, line 33
	} // library marker zwaveTools.openSmarthouseTools, line 34

	reparsed.deviceInputs = deviceData.deviceInputs?.collectEntries{ k, v -> [(k as Integer), (v)] } // library marker zwaveTools.openSmarthouseTools, line 36
	reparsed.fingerprints = deviceData.fingerprints?.collect{ it -> [manufacturer:(it.manufacturer as Integer), deviceId:(it.deviceId as Integer),  deviceType:(it.deviceType as Integer), name:(it.name)] } // library marker zwaveTools.openSmarthouseTools, line 37
	if (deviceData.classVersions) reparsed.classVersions = deviceData.classVersions?.collectEntries{ k, v -> [(k as Integer), (v as Integer)] } // library marker zwaveTools.openSmarthouseTools, line 38
	if (logEnable) "Device ${device.displayName}: Reparsed data is ${reparsed}" // library marker zwaveTools.openSmarthouseTools, line 39
	return reparsed // library marker zwaveTools.openSmarthouseTools, line 40
} // library marker zwaveTools.openSmarthouseTools, line 41


Map getSpecificRecord(id) // library marker zwaveTools.openSmarthouseTools, line 44
{ // library marker zwaveTools.openSmarthouseTools, line 45
    String queryByDatabaseID= "http://www.opensmarthouse.org/dmxConnect/api/zwavedatabase/device/read.php?device_id=${id}"     // library marker zwaveTools.openSmarthouseTools, line 46

	httpGet([uri:queryByDatabaseID]) { resp->  // library marker zwaveTools.openSmarthouseTools, line 48
				return resp?.data // library marker zwaveTools.openSmarthouseTools, line 49
			} // library marker zwaveTools.openSmarthouseTools, line 50
} // library marker zwaveTools.openSmarthouseTools, line 51

Map openSmarthouseCreateDeviceDataRecord() // library marker zwaveTools.openSmarthouseTools, line 53
{ // library marker zwaveTools.openSmarthouseTools, line 54
	Map firstQueryRecord = getOpenSmartHouseData(); // library marker zwaveTools.openSmarthouseTools, line 55

	if (firstQueryRecord.is( null )) { // library marker zwaveTools.openSmarthouseTools, line 57
	log.error "Device ${device.displayName}: Failed to retrieve data record identifier for device from OpenSmartHouse Z-Wave database. OpenSmartHouse database may be unavailable. Try again later or check database to see if your device can be found in the database." // library marker zwaveTools.openSmarthouseTools, line 58
	} // library marker zwaveTools.openSmarthouseTools, line 59

	Map thisRecord = getSpecificRecord(firstQueryRecord.id) // library marker zwaveTools.openSmarthouseTools, line 61

	if (thisRecord.is( null )) { // library marker zwaveTools.openSmarthouseTools, line 63
	log.error "Device ${device.displayName}: Failed to retrieve data record for device from OpenSmartHouse Z-Wave database. OpenSmartHouse database may be unavailable. Try again later or check database to see if your device can be found in the database." // library marker zwaveTools.openSmarthouseTools, line 64
	} // library marker zwaveTools.openSmarthouseTools, line 65

	Map deviceRecord = [fingerprints: [] , endpoints: [:] , deviceInputs: null ] // library marker zwaveTools.openSmarthouseTools, line 67
	Map thisFingerprint = [manufacturer: (getDataValue("manufacturer")?.toInteger()) , deviceId: (getDataValue("deviceId")?.toInteger()) ,  deviceType: (getDataValue("deviceType")?.toInteger()) ] // library marker zwaveTools.openSmarthouseTools, line 68
	thisFingerprint.name = "${firstQueryRecord.manufacturer_name}: ${firstQueryRecord.label}" as String // library marker zwaveTools.openSmarthouseTools, line 69

	deviceRecord.fingerprints.add(thisFingerprint ) // library marker zwaveTools.openSmarthouseTools, line 71

	deviceRecord.deviceInputs = createInputControls(thisRecord.parameters) // library marker zwaveTools.openSmarthouseTools, line 73
	deviceRecord.classVersions = getRootClassData(thisRecord.endpoints) // library marker zwaveTools.openSmarthouseTools, line 74
	deviceRecord.endpoints = getEndpointClassData(thisRecord.endpoints) // library marker zwaveTools.openSmarthouseTools, line 75
	deviceRecord.formatVersion = dataRecordFormatVersion // library marker zwaveTools.openSmarthouseTools, line 76

	return deviceRecord // library marker zwaveTools.openSmarthouseTools, line 78
} // library marker zwaveTools.openSmarthouseTools, line 79

// List getOpenSmartHouseData() // library marker zwaveTools.openSmarthouseTools, line 81
Map getOpenSmartHouseData() // library marker zwaveTools.openSmarthouseTools, line 82
{ // library marker zwaveTools.openSmarthouseTools, line 83
	if (txtEnable) log.info "Getting data from OpenSmartHouse for device ${device.displayName}." // library marker zwaveTools.openSmarthouseTools, line 84
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2) // library marker zwaveTools.openSmarthouseTools, line 85
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2) // library marker zwaveTools.openSmarthouseTools, line 86
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2) // library marker zwaveTools.openSmarthouseTools, line 87

    String DeviceInfoURI = "http://www.opensmarthouse.org/dmxConnect/api/zwavedatabase/device/list.php?filter=manufacturer:0x${manufacturer}%20${deviceType}:${deviceID}" // library marker zwaveTools.openSmarthouseTools, line 89

    Map thisDeviceData // library marker zwaveTools.openSmarthouseTools, line 91

    httpGet([uri:DeviceInfoURI]) // library marker zwaveTools.openSmarthouseTools, line 93
    {  // library marker zwaveTools.openSmarthouseTools, line 94
		resp -> // library marker zwaveTools.openSmarthouseTools, line 95
		Map maxRecord = resp.data.devices.max(			{ a, b ->  // library marker zwaveTools.openSmarthouseTools, line 96
				List<Integer> a_version = a.version_max.split("\\.") // library marker zwaveTools.openSmarthouseTools, line 97
				List<Integer> b_version = b.version_max.split("\\.") // library marker zwaveTools.openSmarthouseTools, line 98

				Float a_value = a_version[0].toFloat() + (a_version[1].toFloat() / 1000) // library marker zwaveTools.openSmarthouseTools, line 100
				Float b_value = b_version[0].toFloat() + (b_version[1].toFloat() / 1000) // library marker zwaveTools.openSmarthouseTools, line 101

				(a_value <=> b_value) // library marker zwaveTools.openSmarthouseTools, line 103
			}) // library marker zwaveTools.openSmarthouseTools, line 104
		return maxRecord // library marker zwaveTools.openSmarthouseTools, line 105
	} // library marker zwaveTools.openSmarthouseTools, line 106
} // library marker zwaveTools.openSmarthouseTools, line 107

Map getRootClassData(endpointRecord) { // library marker zwaveTools.openSmarthouseTools, line 109
	endpointRecord.find{ it.number == 0}.commandclass.collectEntries{thisClass -> // library marker zwaveTools.openSmarthouseTools, line 110
					[(classMappings.get(thisClass.commandclass_name, 0) as Integer), (thisClass.version as Integer)] // library marker zwaveTools.openSmarthouseTools, line 111
				} // library marker zwaveTools.openSmarthouseTools, line 112
} // library marker zwaveTools.openSmarthouseTools, line 113

String getChildComponentDriver(List classes) // library marker zwaveTools.openSmarthouseTools, line 115
{ // library marker zwaveTools.openSmarthouseTools, line 116
	if (classes.contains(0x25) ){  // Binary Switch // library marker zwaveTools.openSmarthouseTools, line 117
		if (classes.contains(0x32) ){ // Meter Supported // library marker zwaveTools.openSmarthouseTools, line 118
			return "Generic Component Metering Switch" // library marker zwaveTools.openSmarthouseTools, line 119
		} else { // library marker zwaveTools.openSmarthouseTools, line 120
			return "Generic Component Switch" // library marker zwaveTools.openSmarthouseTools, line 121
		} // library marker zwaveTools.openSmarthouseTools, line 122
	} else  if (classes.contains(0x26)){ // MultiLevel Switch // library marker zwaveTools.openSmarthouseTools, line 123
		if (classes.contains(0x32) ){ // Meter Supported // library marker zwaveTools.openSmarthouseTools, line 124
			return "Generic Component Metering Dimmer" // library marker zwaveTools.openSmarthouseTools, line 125
		} else { // library marker zwaveTools.openSmarthouseTools, line 126
			return "Generic Component Dimmer" // library marker zwaveTools.openSmarthouseTools, line 127
		}			 // library marker zwaveTools.openSmarthouseTools, line 128
	} // library marker zwaveTools.openSmarthouseTools, line 129
	return "Generic Component Dimmer" // library marker zwaveTools.openSmarthouseTools, line 130
} // library marker zwaveTools.openSmarthouseTools, line 131

Map getEndpointClassData(endpointRecord) // library marker zwaveTools.openSmarthouseTools, line 133
{ // library marker zwaveTools.openSmarthouseTools, line 134
	Map endpointClassMap = [:] // library marker zwaveTools.openSmarthouseTools, line 135

	endpointRecord.each{ it -> // library marker zwaveTools.openSmarthouseTools, line 137
			List thisEndpointClasses =  it.commandclass.collect{thisClass -> classMappings.get(thisClass.commandclass_name, 0) as Integer } // library marker zwaveTools.openSmarthouseTools, line 138

			if (it.number == 0) { // library marker zwaveTools.openSmarthouseTools, line 140
				endpointClassMap.put((it.number as Integer), [classes:(thisEndpointClasses)]) // library marker zwaveTools.openSmarthouseTools, line 141
				return // library marker zwaveTools.openSmarthouseTools, line 142
			} else { // library marker zwaveTools.openSmarthouseTools, line 143
				String childDriver = getChildComponentDriver(thisEndpointClasses) // library marker zwaveTools.openSmarthouseTools, line 144
				endpointClassMap.put((it.number as Integer), [children:[[type:childDriver, namespace:"hubitat"]], classes:(thisEndpointClasses)]) // library marker zwaveTools.openSmarthouseTools, line 145
			} // library marker zwaveTools.openSmarthouseTools, line 146
		} // library marker zwaveTools.openSmarthouseTools, line 147
    return endpointClassMap // library marker zwaveTools.openSmarthouseTools, line 148
} // library marker zwaveTools.openSmarthouseTools, line 149

Map createInputControls(data) // library marker zwaveTools.openSmarthouseTools, line 151
{ // library marker zwaveTools.openSmarthouseTools, line 152
	if (!data) return null // library marker zwaveTools.openSmarthouseTools, line 153

	Map inputControls = [:]	 // library marker zwaveTools.openSmarthouseTools, line 155
	data?.each // library marker zwaveTools.openSmarthouseTools, line 156
	{ // library marker zwaveTools.openSmarthouseTools, line 157
		if (it.read_only as Integer) { // library marker zwaveTools.openSmarthouseTools, line 158
				log.info "Device ${device.displayName}: Parameter ${it.param_id}-${it.label} is read-only. No input control created." // library marker zwaveTools.openSmarthouseTools, line 159
				return // library marker zwaveTools.openSmarthouseTools, line 160
			} // library marker zwaveTools.openSmarthouseTools, line 161

		if (it.bitmask.toInteger()) // library marker zwaveTools.openSmarthouseTools, line 163
		{ // library marker zwaveTools.openSmarthouseTools, line 164
			if (!(inputControls?.get(it.param_id))) // library marker zwaveTools.openSmarthouseTools, line 165
			{ // library marker zwaveTools.openSmarthouseTools, line 166
				log.warn "Device ${device.displayName}: Parameter ${it.param_id} is a bitmap field. This is poorly supported. Treating as an integer - rely on your user manual for proper values!" // library marker zwaveTools.openSmarthouseTools, line 167
				String param_name_string = "${it.param_id}" // library marker zwaveTools.openSmarthouseTools, line 168
				String title_string = "(${it.param_id}) ${it.label} - bitmap" // library marker zwaveTools.openSmarthouseTools, line 169
				Map newInput = [name:param_name_string , type:"number", title: title_string, size:it.size] // library marker zwaveTools.openSmarthouseTools, line 170
				if ((it.description.size() != 0) && (it.description != it.label)) newInput.description = it.description // library marker zwaveTools.openSmarthouseTools, line 171

				inputControls.put(it.param_id, newInput) // library marker zwaveTools.openSmarthouseTools, line 173
			} // library marker zwaveTools.openSmarthouseTools, line 174
		} else { // library marker zwaveTools.openSmarthouseTools, line 175
			String param_name_string = "${it.param_id}" // library marker zwaveTools.openSmarthouseTools, line 176
			String title_string = "(${it.param_id}) ${it.label}" // library marker zwaveTools.openSmarthouseTools, line 177

			Map newInput = [name: param_name_string, title: title_string, size:it.size] // library marker zwaveTools.openSmarthouseTools, line 179
			if ((it.description.size() != 0) && (it.description != it.label)) newInput.description = it.description // library marker zwaveTools.openSmarthouseTools, line 180

			def deviceOptions = [:] // library marker zwaveTools.openSmarthouseTools, line 182
			it.options.each { deviceOptions.put(it.value, it.label) } // library marker zwaveTools.openSmarthouseTools, line 183

			// Set input type. Should be one of: bool, date, decimal, email, enum, number, password, time, text. See: https://docs.hubitat.com/index.php?title=Device_Preferences // library marker zwaveTools.openSmarthouseTools, line 185

			// use enum but only if it covers all of the choices! // library marker zwaveTools.openSmarthouseTools, line 187
			Integer numberOfValues = (it.maximum - it.minimum) +1 // library marker zwaveTools.openSmarthouseTools, line 188
			if (deviceOptions && (deviceOptions.size() == numberOfValues) ) // library marker zwaveTools.openSmarthouseTools, line 189
			{ // library marker zwaveTools.openSmarthouseTools, line 190
				newInput.type = "enum" // library marker zwaveTools.openSmarthouseTools, line 191
				newInput.options = deviceOptions // library marker zwaveTools.openSmarthouseTools, line 192
			} else { // library marker zwaveTools.openSmarthouseTools, line 193
				newInput.type = "number" // library marker zwaveTools.openSmarthouseTools, line 194
				newInput.range = "${it.minimum}..${it.maximum}" // library marker zwaveTools.openSmarthouseTools, line 195
			} // library marker zwaveTools.openSmarthouseTools, line 196
			inputControls[it.param_id] = newInput // library marker zwaveTools.openSmarthouseTools, line 197
		} // library marker zwaveTools.openSmarthouseTools, line 198
	} // library marker zwaveTools.openSmarthouseTools, line 199
	return inputControls // library marker zwaveTools.openSmarthouseTools, line 200
} // library marker zwaveTools.openSmarthouseTools, line 201

@Field static Map classMappings = [ // library marker zwaveTools.openSmarthouseTools, line 203
	COMMAND_CLASS_ALARM:0x71, // library marker zwaveTools.openSmarthouseTools, line 204
	COMMAND_CLASS_SENSOR_ALARM :0x9C, // library marker zwaveTools.openSmarthouseTools, line 205
	COMMAND_CLASS_SILENCE_ALARM:0x9D, // library marker zwaveTools.openSmarthouseTools, line 206
	COMMAND_CLASS_SWITCH_ALL:0x27, // library marker zwaveTools.openSmarthouseTools, line 207
	COMMAND_CLASS_ANTITHEFT:0x5D, // library marker zwaveTools.openSmarthouseTools, line 208
	COMMAND_CLASS_ANTITHEFT_UNLOCK:0x7E, // library marker zwaveTools.openSmarthouseTools, line 209
	COMMAND_CLASS_APPLICATION_CAPABILITY:0x57, // library marker zwaveTools.openSmarthouseTools, line 210
	COMMAND_CLASS_APPLICATION_STATUS:0x22, // library marker zwaveTools.openSmarthouseTools, line 211
	COMMAND_CLASS_ASSOCIATION:0x85, // library marker zwaveTools.openSmarthouseTools, line 212
	COMMAND_CLASS_ASSOCIATION_COMMAND_CONFIGURATION:0x9B, // library marker zwaveTools.openSmarthouseTools, line 213
	COMMAND_CLASS_ASSOCIATION_GRP_INFO:0x59, // library marker zwaveTools.openSmarthouseTools, line 214
	COMMAND_CLASS_AUTHENTICATION:0xA1, // library marker zwaveTools.openSmarthouseTools, line 215
	COMMAND_CLASS_AUTHENTICATION_MEDIA_WRITE:0xA2, // library marker zwaveTools.openSmarthouseTools, line 216
	COMMAND_CLASS_BARRIER_OPERATOR:0x66, // library marker zwaveTools.openSmarthouseTools, line 217
	COMMAND_CLASS_BASIC:0x20, // library marker zwaveTools.openSmarthouseTools, line 218
	COMMAND_CLASS_BASIC_TARIFF_INFO:0x36, // library marker zwaveTools.openSmarthouseTools, line 219
	COMMAND_CLASS_BASIC_WINDOW_COVERING:0x50, // library marker zwaveTools.openSmarthouseTools, line 220
	COMMAND_CLASS_BATTERY:0x80, // library marker zwaveTools.openSmarthouseTools, line 221
	COMMAND_CLASS_SENSOR_BINARY:0x30, // library marker zwaveTools.openSmarthouseTools, line 222
	COMMAND_CLASS_SWITCH_BINARY:0x25, // library marker zwaveTools.openSmarthouseTools, line 223
	COMMAND_CLASS_SWITCH_TOGGLE_BINARY:0x28, // library marker zwaveTools.openSmarthouseTools, line 224
	COMMAND_CLASS_CLIMATE_CONTROL_SCHEDULE:0x46, // library marker zwaveTools.openSmarthouseTools, line 225
	COMMAND_CLASS_CENTRAL_SCENE:0x5B, // library marker zwaveTools.openSmarthouseTools, line 226
	COMMAND_CLASS_CLOCK:0x81, // library marker zwaveTools.openSmarthouseTools, line 227
	COMMAND_CLASS_SWITCH_COLOR:0x33, // library marker zwaveTools.openSmarthouseTools, line 228
	COMMAND_CLASS_CONFIGURATION:0x70, // library marker zwaveTools.openSmarthouseTools, line 229
	COMMAND_CLASS_CONTROLLER_REPLICATION:0x21, // library marker zwaveTools.openSmarthouseTools, line 230
	COMMAND_CLASS_CRC_16_ENCAP:0x56, // library marker zwaveTools.openSmarthouseTools, line 231
	COMMAND_CLASS_DCP_CONFIG:0x3A, // library marker zwaveTools.openSmarthouseTools, line 232
	COMMAND_CLASS_DCP_MONITOR:0x3B, // library marker zwaveTools.openSmarthouseTools, line 233
	COMMAND_CLASS_DEVICE_RESET_LOCALLY:0x5A, // library marker zwaveTools.openSmarthouseTools, line 234
	COMMAND_CLASS_DOOR_LOCK:0x62, // library marker zwaveTools.openSmarthouseTools, line 235
	COMMAND_CLASS_DOOR_LOCK_LOGGING:0x4C, // library marker zwaveTools.openSmarthouseTools, line 236
	COMMAND_CLASS_ENERGY_PRODUCTION:0x90, // library marker zwaveTools.openSmarthouseTools, line 237
	COMMAND_CLASS_ENTRY_CONTROL :0x6F, // library marker zwaveTools.openSmarthouseTools, line 238
	COMMAND_CLASS_FIRMWARE_UPDATE_MD:0x7A, // library marker zwaveTools.openSmarthouseTools, line 239
	COMMAND_CLASS_GENERIC_SCHEDULE:0xA3, // library marker zwaveTools.openSmarthouseTools, line 240
	COMMAND_CLASS_GEOGRAPHIC_LOCATION:0x8C, // library marker zwaveTools.openSmarthouseTools, line 241
	COMMAND_CLASS_GROUPING_NAME:0x7B, // library marker zwaveTools.openSmarthouseTools, line 242
	COMMAND_CLASS_HAIL:0x82, // library marker zwaveTools.openSmarthouseTools, line 243
	COMMAND_CLASS_HRV_STATUS:0x37, // library marker zwaveTools.openSmarthouseTools, line 244
	COMMAND_CLASS_HRV_CONTROL:0x39, // library marker zwaveTools.openSmarthouseTools, line 245
	COMMAND_CLASS_HUMIDITY_CONTROL_MODE:0x6D, // library marker zwaveTools.openSmarthouseTools, line 246
	COMMAND_CLASS_HUMIDITY_CONTROL_OPERATING_STATE:0x6E, // library marker zwaveTools.openSmarthouseTools, line 247
	COMMAND_CLASS_HUMIDITY_CONTROL_SETPOINT:0x64, // library marker zwaveTools.openSmarthouseTools, line 248
	COMMAND_CLASS_INCLUSION_CONTROLLER:0x74, // library marker zwaveTools.openSmarthouseTools, line 249
	COMMAND_CLASS_INDICATOR:0x87, // library marker zwaveTools.openSmarthouseTools, line 250
	COMMAND_CLASS_IP_ASSOCIATION:0x5C, // library marker zwaveTools.openSmarthouseTools, line 251
	COMMAND_CLASS_IP_CONFIGURATION:0x9A, // library marker zwaveTools.openSmarthouseTools, line 252
	COMMAND_CLASS_IR_REPEATER:0xA0, // library marker zwaveTools.openSmarthouseTools, line 253
	COMMAND_CLASS_IRRIGATION:0x6B, // library marker zwaveTools.openSmarthouseTools, line 254
	COMMAND_CLASS_LANGUAGE:0x89, // library marker zwaveTools.openSmarthouseTools, line 255
	COMMAND_CLASS_LOCK:0x76, // library marker zwaveTools.openSmarthouseTools, line 256
	COMMAND_CLASS_MAILBOX:0x69, // library marker zwaveTools.openSmarthouseTools, line 257
	COMMAND_CLASS_MANUFACTURER_PROPRIETARY:0x91, // library marker zwaveTools.openSmarthouseTools, line 258
	COMMAND_CLASS_MANUFACTURER_SPECIFIC:0x72, // library marker zwaveTools.openSmarthouseTools, line 259
	COMMAND_CLASS_MARK:0xEF, // library marker zwaveTools.openSmarthouseTools, line 260
	COMMAND_CLASS_METER:0x32, // library marker zwaveTools.openSmarthouseTools, line 261
	COMMAND_CLASS_METER_TBL_CONFIG:0x3C, // library marker zwaveTools.openSmarthouseTools, line 262
	COMMAND_CLASS_METER_TBL_MONITOR:0x3D, // library marker zwaveTools.openSmarthouseTools, line 263
	COMMAND_CLASS_METER_TBL_PUSH:0x3E, // library marker zwaveTools.openSmarthouseTools, line 264
	COMMAND_CLASS_MTP_WINDOW_COVERING:0x51, // library marker zwaveTools.openSmarthouseTools, line 265
	COMMAND_CLASS_MULTI_CHANNEL:0x60, // library marker zwaveTools.openSmarthouseTools, line 266
	COMMAND_CLASS_MULTI_CHANNEL_ASSOCIATION:0x8E, // library marker zwaveTools.openSmarthouseTools, line 267
	COMMAND_CLASS_MULTI_CMD:0x8F, // library marker zwaveTools.openSmarthouseTools, line 268
	COMMAND_CLASS_SENSOR_MULTILEVEL:0x31, // library marker zwaveTools.openSmarthouseTools, line 269
	COMMAND_CLASS_SWITCH_MULTILEVEL:0x26, // library marker zwaveTools.openSmarthouseTools, line 270
	COMMAND_CLASS_SWITCH_TOGGLE_MULTILEVEL:0x29, // library marker zwaveTools.openSmarthouseTools, line 271
	COMMAND_CLASS_NETWORK_MANAGEMENT_BASIC:0x4D, // library marker zwaveTools.openSmarthouseTools, line 272
	COMMAND_CLASS_NETWORK_MANAGEMENT_INCLUSION:0x34, // library marker zwaveTools.openSmarthouseTools, line 273
	NETWORK_MANAGEMENT_INSTALLATION_MAINTENANCE:0x67, // library marker zwaveTools.openSmarthouseTools, line 274
	COMMAND_CLASS_NETWORK_MANAGEMENT_PRIMARY:0x54, // library marker zwaveTools.openSmarthouseTools, line 275
	COMMAND_CLASS_NETWORK_MANAGEMENT_PROXY:0x52, // library marker zwaveTools.openSmarthouseTools, line 276
	COMMAND_CLASS_NO_OPERATION:0x00, // library marker zwaveTools.openSmarthouseTools, line 277
	COMMAND_CLASS_NODE_NAMING:0x77, // library marker zwaveTools.openSmarthouseTools, line 278
	COMMAND_CLASS_NODE_PROVISIONING:0x78, // library marker zwaveTools.openSmarthouseTools, line 279
	COMMAND_CLASS_NOTIFICATION:0x71, // library marker zwaveTools.openSmarthouseTools, line 280
	COMMAND_CLASS_POWERLEVEL:0x73, // library marker zwaveTools.openSmarthouseTools, line 281
	COMMAND_CLASS_PREPAYMENT:0x3F, // library marker zwaveTools.openSmarthouseTools, line 282
	COMMAND_CLASS_PREPAYMENT_ENCAPSULATION:0x41, // library marker zwaveTools.openSmarthouseTools, line 283
	COMMAND_CLASS_PROPRIETARY:0x88, // library marker zwaveTools.openSmarthouseTools, line 284
	COMMAND_CLASS_PROTECTION:0x75, // library marker zwaveTools.openSmarthouseTools, line 285
	COMMAND_CLASS_METER_PULSE:0x35, // library marker zwaveTools.openSmarthouseTools, line 286
	COMMAND_CLASS_RATE_TBL_CONFIG:0x48, // library marker zwaveTools.openSmarthouseTools, line 287
	COMMAND_CLASS_RATE_TBL_MONITOR:0x49, // library marker zwaveTools.openSmarthouseTools, line 288
	COMMAND_CLASS_REMOTE_ASSOCIATION_ACTIVATE:0x7C, // library marker zwaveTools.openSmarthouseTools, line 289
	COMMAND_CLASS_REMOTE_ASSOCIATION:0x7D, // library marker zwaveTools.openSmarthouseTools, line 290
	COMMAND_CLASS_SCENE_ACTIVATION:0x2B, // library marker zwaveTools.openSmarthouseTools, line 291
	COMMAND_CLASS_SCENE_ACTUATOR_CONF:0x2C, // library marker zwaveTools.openSmarthouseTools, line 292
	COMMAND_CLASS_SCENE_CONTROLLER_CONF:0x2D, // library marker zwaveTools.openSmarthouseTools, line 293
	COMMAND_CLASS_SCHEDULE:0x53, // library marker zwaveTools.openSmarthouseTools, line 294
	COMMAND_CLASS_SCHEDULE_ENTRY_LOCK:0x4E, // library marker zwaveTools.openSmarthouseTools, line 295
	COMMAND_CLASS_SCREEN_ATTRIBUTES:0x93, // library marker zwaveTools.openSmarthouseTools, line 296
	COMMAND_CLASS_SCREEN_MD:0x92, // library marker zwaveTools.openSmarthouseTools, line 297
	COMMAND_CLASS_SECURITY:0x98, // library marker zwaveTools.openSmarthouseTools, line 298
	COMMAND_CLASS_SECURITY_2:0x9F, // library marker zwaveTools.openSmarthouseTools, line 299
	COMMAND_CLASS_SECURITY_SCHEME0_MARK :0xF100, // library marker zwaveTools.openSmarthouseTools, line 300
	COMMAND_CLASS_SENSOR_CONFIGURATION:0x9E, // library marker zwaveTools.openSmarthouseTools, line 301
	COMMAND_CLASS_SIMPLE_AV_CONTROL:0x94, // library marker zwaveTools.openSmarthouseTools, line 302
	COMMAND_CLASS_SOUND_SWITCH:0x79, // library marker zwaveTools.openSmarthouseTools, line 303
	COMMAND_CLASS_SUPERVISION:0x6C, // library marker zwaveTools.openSmarthouseTools, line 304
	COMMAND_CLASS_TARIFF_CONFIG:0x4A, // library marker zwaveTools.openSmarthouseTools, line 305
	COMMAND_CLASS_TARIFF_TBL_MONITOR:0x4B, // library marker zwaveTools.openSmarthouseTools, line 306
	COMMAND_CLASS_THERMOSTAT_FAN_MODE:0x44, // library marker zwaveTools.openSmarthouseTools, line 307
	COMMAND_CLASS_THERMOSTAT_FAN_STATE:0x45, // library marker zwaveTools.openSmarthouseTools, line 308
	COMMAND_CLASS_THERMOSTAT_MODE:0x40, // library marker zwaveTools.openSmarthouseTools, line 309
	COMMAND_CLASS_THERMOSTAT_OPERATING_STATE:0x42, // library marker zwaveTools.openSmarthouseTools, line 310
	COMMAND_CLASS_THERMOSTAT_SETBACK:0x47, // library marker zwaveTools.openSmarthouseTools, line 311
	COMMAND_CLASS_THERMOSTAT_SETPOINT:0x43, // library marker zwaveTools.openSmarthouseTools, line 312
	COMMAND_CLASS_TIME:0x8A, // library marker zwaveTools.openSmarthouseTools, line 313
	COMMAND_CLASS_TIME_PARAMETERS:0x8B, // library marker zwaveTools.openSmarthouseTools, line 314
	COMMAND_CLASS_TRANSPORT_SERVICE:0x55, // library marker zwaveTools.openSmarthouseTools, line 315
	COMMAND_CLASS_USER_CODE:0x63, // library marker zwaveTools.openSmarthouseTools, line 316
	COMMAND_CLASS_VERSION:0x86, // library marker zwaveTools.openSmarthouseTools, line 317
	COMMAND_CLASS_WAKE_UP:0x84, // library marker zwaveTools.openSmarthouseTools, line 318
	COMMAND_CLASS_WINDOW_COVERING:0x6A, // library marker zwaveTools.openSmarthouseTools, line 319
	COMMAND_CLASS_ZIP:0x23, // library marker zwaveTools.openSmarthouseTools, line 320
	COMMAND_CLASS_ZIP_6LOWPAN:0x4F, // library marker zwaveTools.openSmarthouseTools, line 321
	COMMAND_CLASS_ZIP_GATEWAY:0x5F, // library marker zwaveTools.openSmarthouseTools, line 322
	COMMAND_CLASS_ZIP_NAMING:0x68, // library marker zwaveTools.openSmarthouseTools, line 323
	COMMAND_CLASS_ZIP_ND:0x58, // library marker zwaveTools.openSmarthouseTools, line 324
	COMMAND_CLASS_ZIP_PORTAL:0x61, // library marker zwaveTools.openSmarthouseTools, line 325
	COMMAND_CLASS_ZWAVEPLUS_INFO:0x5E, // library marker zwaveTools.openSmarthouseTools, line 326
] // library marker zwaveTools.openSmarthouseTools, line 327

// ~~~~~ end include (132) zwaveTools.openSmarthouseTools ~~~~~

// ~~~~~ start include (164) zwaveTools.childDeviceTools ~~~~~
library ( // library marker zwaveTools.childDeviceTools, line 1
        base: "driver", // library marker zwaveTools.childDeviceTools, line 2
        author: "jvm33", // library marker zwaveTools.childDeviceTools, line 3
        category: "zwave", // library marker zwaveTools.childDeviceTools, line 4
        description: "Child device Support Functions", // library marker zwaveTools.childDeviceTools, line 5
        name: "childDeviceTools", // library marker zwaveTools.childDeviceTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.childDeviceTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.childDeviceTools, line 8
		version: "0.0.1", // library marker zwaveTools.childDeviceTools, line 9
		dependencies: "child creation depends on the thisDeviceDataRecord data record - can this be removed?", // library marker zwaveTools.childDeviceTools, line 10
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/endpointTools.groovy" // library marker zwaveTools.childDeviceTools, line 11
) // library marker zwaveTools.childDeviceTools, line 12

///////////////////////////////////////////////////////////////////////// // library marker zwaveTools.childDeviceTools, line 14
//////        Create and Manage Child Devices for Endpoints       /////// // library marker zwaveTools.childDeviceTools, line 15
///////////////////////////////////////////////////////////////////////// // library marker zwaveTools.childDeviceTools, line 16

void deleteUnwantedChildDevices() // library marker zwaveTools.childDeviceTools, line 18
{	 // library marker zwaveTools.childDeviceTools, line 19
	// Delete child devices that don't use the proper network ID form (parent ID, followed by "-ep" followed by endpoint number). // library marker zwaveTools.childDeviceTools, line 20
	getChildDevices()?.each // library marker zwaveTools.childDeviceTools, line 21
	{ child ->	 // library marker zwaveTools.childDeviceTools, line 22

		List childNetIdComponents = child.deviceNetworkId.split("-ep") // library marker zwaveTools.childDeviceTools, line 24
		if ((getFullEndpointRecord().containsKey(childNetIdComponents[1] as Integer)) && (childNetIdComponents[0]?.startsWith(device.deviceNetworkId)) ) { // library marker zwaveTools.childDeviceTools, line 25
			return // library marker zwaveTools.childDeviceTools, line 26
		} else { // library marker zwaveTools.childDeviceTools, line 27
			deleteChildDevice(child.deviceNetworkId) // library marker zwaveTools.childDeviceTools, line 28
		}			 // library marker zwaveTools.childDeviceTools, line 29
	} // library marker zwaveTools.childDeviceTools, line 30
} // library marker zwaveTools.childDeviceTools, line 31


void createChildDevices() // library marker zwaveTools.childDeviceTools, line 34
{	 // library marker zwaveTools.childDeviceTools, line 35
	getFullEndpointRecord()?.each // library marker zwaveTools.childDeviceTools, line 36
	{ ep, endpointRecord -> // library marker zwaveTools.childDeviceTools, line 37

		endpointRecord.children?.eachWithIndex { thisChildItem, index -> // library marker zwaveTools.childDeviceTools, line 39
			String childNetworkId = getChildNetID(ep, index) // library marker zwaveTools.childDeviceTools, line 40
			com.hubitat.app.DeviceWrapper cd = getChildDevice(childNetworkId) // library marker zwaveTools.childDeviceTools, line 41
			if (cd.is( null )) { // library marker zwaveTools.childDeviceTools, line 42
				log.info "Device ${device.displayName}: creating child device: ${childNetworkId} with driver ${thisChildItem.type} and namespace: ${thisChildItem.namespace}." // library marker zwaveTools.childDeviceTools, line 43

				addChildDevice(thisChildItem.namespace, thisChildItem.type, childNetworkId, [name: device.displayName, isComponent: false]) // library marker zwaveTools.childDeviceTools, line 45
			}  // library marker zwaveTools.childDeviceTools, line 46
		} // library marker zwaveTools.childDeviceTools, line 47
	} // library marker zwaveTools.childDeviceTools, line 48
} // library marker zwaveTools.childDeviceTools, line 49
// // library marker zwaveTools.childDeviceTools, line 50
		command "addNewChildDevice", [[name:"Device Name*", type:"STRING"],  // library marker zwaveTools.childDeviceTools, line 51
                                      [name:"componentDriverName*",type:"ENUM", constraints:(getDriverChoices()) ],  // library marker zwaveTools.childDeviceTools, line 52
                                      [name:"Endpoint*",type:"NUMBER", description:"Endpoint Number, Use 0 for root (parent) device" ] ] // library marker zwaveTools.childDeviceTools, line 53

// // library marker zwaveTools.childDeviceTools, line 55

List getDriverChoices() { // library marker zwaveTools.childDeviceTools, line 57
	// Returns the name of the generic component drivers with their namespace listed in parenthesis // library marker zwaveTools.childDeviceTools, line 58
    // log.debug getInstalledDrivers().findAll{it.name.toLowerCase().startsWith("generic component")}.collect{ "${it.name} (${it.namespace})"}.sort() // library marker zwaveTools.childDeviceTools, line 59
    return getInstalledDrivers().findAll{it.name.toLowerCase().startsWith("generic component")}.collect{ "${it.name} (${it.namespace})"}.sort() // library marker zwaveTools.childDeviceTools, line 60
} // library marker zwaveTools.childDeviceTools, line 61

String getChildNetID(Integer ep, Integer index){ // library marker zwaveTools.childDeviceTools, line 63
	return "${device.deviceNetworkId}.child${index}-ep${"${ep}".padLeft(3, "0") }" // library marker zwaveTools.childDeviceTools, line 64
} // library marker zwaveTools.childDeviceTools, line 65

String getNextChild(Integer ep) // library marker zwaveTools.childDeviceTools, line 67
{ // library marker zwaveTools.childDeviceTools, line 68
	Integer thisChild = 1 // library marker zwaveTools.childDeviceTools, line 69
	String rValue = getChildNetID(ep, thisChild) // library marker zwaveTools.childDeviceTools, line 70

	while ( getChildDevice(rValue) ) {  // library marker zwaveTools.childDeviceTools, line 72
			thisChild ++  // library marker zwaveTools.childDeviceTools, line 73
			rValue = getChildNetID(ep, thisChild) // library marker zwaveTools.childDeviceTools, line 74
		} // library marker zwaveTools.childDeviceTools, line 75
	return rValue // library marker zwaveTools.childDeviceTools, line 76
} // library marker zwaveTools.childDeviceTools, line 77
void addNewChildDevice(String newChildName, String componentDriverName, endpoint) { // library marker zwaveTools.childDeviceTools, line 78
	// log.debug "Driver name is ${newChildName} with driver component type ${componentDriverName} for endpoint ${endpoint}" // library marker zwaveTools.childDeviceTools, line 79
	Map thisDriver = getInstalledDrivers().find{ "${it.name} (${it.namespace})" == componentDriverName } // library marker zwaveTools.childDeviceTools, line 80

	// log.debug "selected driver is ${thisDriver}" // library marker zwaveTools.childDeviceTools, line 82
	String thisChildNetworkId = getNextChild((int) (endpoint ?: 0)) // library marker zwaveTools.childDeviceTools, line 83
	addChildDevice(thisDriver.namespace, thisDriver.name, thisChildNetworkId, [name: newChildName, isComponent: false]) // library marker zwaveTools.childDeviceTools, line 84
} // library marker zwaveTools.childDeviceTools, line 85

///////////////////////////////////////////////////////////////// // library marker zwaveTools.childDeviceTools, line 87

// ~~~~~ end include (164) zwaveTools.childDeviceTools ~~~~~

// ~~~~~ start include (165) zwaveTools.parameterManagementTools ~~~~~
library ( // library marker zwaveTools.parameterManagementTools, line 1
        base: "driver", // library marker zwaveTools.parameterManagementTools, line 2
        author: "jvm33", // library marker zwaveTools.parameterManagementTools, line 3
        category: "zwave", // library marker zwaveTools.parameterManagementTools, line 4
        description: "Tools to manage device parameters and other device-specific data.", // library marker zwaveTools.parameterManagementTools, line 5
        name: "parameterManagementTools", // library marker zwaveTools.parameterManagementTools, line 6
        namespace: "zwaveTools", // library marker zwaveTools.parameterManagementTools, line 7
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools", // library marker zwaveTools.parameterManagementTools, line 8
		version: "0.0.1", // library marker zwaveTools.parameterManagementTools, line 9
		dependencies: "", // library marker zwaveTools.parameterManagementTools, line 10
		librarySource:"https://github.com/jvmahon/HubitatDriverTools/blob/main/parameterManagementTools.groovy" // library marker zwaveTools.parameterManagementTools, line 11
) // library marker zwaveTools.parameterManagementTools, line 12

import java.util.concurrent.*  // library marker zwaveTools.parameterManagementTools, line 14
import groovy.transform.Field // library marker zwaveTools.parameterManagementTools, line 15


void updated() // library marker zwaveTools.parameterManagementTools, line 18
{ // library marker zwaveTools.parameterManagementTools, line 19
	if (txtEnable) log.info "Device ${device.displayName}: Updating changed parameters (if any) . . ." // library marker zwaveTools.parameterManagementTools, line 20
	if (logEnable) runIn(1800,logsOff) // library marker zwaveTools.parameterManagementTools, line 21

	ConcurrentHashMap<Integer, BigInteger> parameterValueMap = getParameterValuesFromDevice() // library marker zwaveTools.parameterManagementTools, line 23
	if (parameterValueMap.is( null )) // library marker zwaveTools.parameterManagementTools, line 24
		{ // library marker zwaveTools.parameterManagementTools, line 25
			log.error "In function updated, parameterValueMap is ${parameterValueMap}" // library marker zwaveTools.parameterManagementTools, line 26
			return // library marker zwaveTools.parameterManagementTools, line 27
		} // library marker zwaveTools.parameterManagementTools, line 28

	ConcurrentHashMap<Integer, BigInteger> pendingChanges = getPendingChangeMap() // library marker zwaveTools.parameterManagementTools, line 30

	Map<Integer, BigInteger>  settingValueMap = getParameterValuesFromInputControls() // library marker zwaveTools.parameterManagementTools, line 32

	pendingChangeMap.putAll(settingValueMap.findAll{it.value} - parameterValueMap) // library marker zwaveTools.parameterManagementTools, line 34

	if (txtEnable) log.info "Device ${device.displayName}: new Setting values are ${settingValueMap}, Last Device Parameters were ${parameterValueMap}, Pending parameter changes are: ${pendingChanges ?: "None"}" // library marker zwaveTools.parameterManagementTools, line 36

	log.debug "new pending change map is ${getPendingChangeMap()}" // library marker zwaveTools.parameterManagementTools, line 38

	processPendingChanges() // library marker zwaveTools.parameterManagementTools, line 40
	if (txtEnable) log.info "Device ${device.displayName}: Done updating changed parameters (if any) . . ." // library marker zwaveTools.parameterManagementTools, line 41

} // library marker zwaveTools.parameterManagementTools, line 43

void processPendingChanges() // library marker zwaveTools.parameterManagementTools, line 45
{ // library marker zwaveTools.parameterManagementTools, line 46
	if (txtEnable) log.info "Device ${device.displayName}: Processing Pending parameter changes: ${getPendingChangeMap()}" // library marker zwaveTools.parameterManagementTools, line 47
		// pendingChangeMap?.findAll{k, v -> !(v.is( null ))}.each{ k, v -> // library marker zwaveTools.parameterManagementTools, line 48
			pendingChangeMap?.each{ k, v -> // library marker zwaveTools.parameterManagementTools, line 49
                if (txtEnable) log.info "Updating parameter ${k} to value ${v}" // library marker zwaveTools.parameterManagementTools, line 50
			setParameter(parameterNumber: k , value: v) // library marker zwaveTools.parameterManagementTools, line 51
		} // library marker zwaveTools.parameterManagementTools, line 52
} // library marker zwaveTools.parameterManagementTools, line 53

void setParameter(parameterNumber, value = null ) { // library marker zwaveTools.parameterManagementTools, line 55
	if (parameterNumber && ( ! value.is( null ) )) { // library marker zwaveTools.parameterManagementTools, line 56
		setParameter(parameterNumber:parameterNumber, value:value) // library marker zwaveTools.parameterManagementTools, line 57
	} else if (parameterNumber) { // library marker zwaveTools.parameterManagementTools, line 58
		advancedZwaveSend( zwave.configurationV1.configurationGet(parameterNumber: parameterNumber)) // library marker zwaveTools.parameterManagementTools, line 59
		hubitat.zwave.Command report = myReportQueue("7006").poll(5, TimeUnit.SECONDS) // library marker zwaveTools.parameterManagementTools, line 60
		if (logEnable) log.debug "Device ${device.displayName}: Received a parameter configuration report: ${report}." // library marker zwaveTools.parameterManagementTools, line 61
	} // library marker zwaveTools.parameterManagementTools, line 62
} // library marker zwaveTools.parameterManagementTools, line 63

Boolean setParameter(Map params = [parameterNumber: null , value: null ] ){ // library marker zwaveTools.parameterManagementTools, line 65
    if (params.parameterNumber.is( null ) || params.value.is( null ) ) { // library marker zwaveTools.parameterManagementTools, line 66
		log.warn "Device ${device.displayName}: Can't set parameter ${parameterNumber}, Incomplete parameter list supplied... syntax: setParameter(parameterNumber,size,value), received: setParameter(${parameterNumber}, ${size}, ${value})." // library marker zwaveTools.parameterManagementTools, line 67
		return false // library marker zwaveTools.parameterManagementTools, line 68
    }  // library marker zwaveTools.parameterManagementTools, line 69

	String getThis = "${params.parameterNumber}" as String // library marker zwaveTools.parameterManagementTools, line 71

	Integer PSize = ( deviceInputs.get(getThis)?.size) ?: (deviceInputs.get(params.parameterNumber as Integer)?.size ) // library marker zwaveTools.parameterManagementTools, line 73

	if (!PSize) {log.error "Device ${device.displayName}: Could not get parameter size in function setParameter. Defaulting to 1"; PSize = 1} // library marker zwaveTools.parameterManagementTools, line 75

	advancedZwaveSend(zwave.configurationV1.configurationSet(scaledConfigurationValue: params.value as BigInteger, parameterNumber: params.parameterNumber, size: PSize)) // library marker zwaveTools.parameterManagementTools, line 77
	// The 'get' should not be supervised! // library marker zwaveTools.parameterManagementTools, line 78
	advancedZwaveSend( zwave.configurationV1.configurationGet(parameterNumber: params.parameterNumber)) // library marker zwaveTools.parameterManagementTools, line 79

	// Wait for the report that is returned after the configurationGet, and then update the input controls so they display the updated value. // library marker zwaveTools.parameterManagementTools, line 81
	Boolean success = myReportQueue("7006").poll(5, TimeUnit.SECONDS) ? true : false  // library marker zwaveTools.parameterManagementTools, line 82

} // library marker zwaveTools.parameterManagementTools, line 84

// Gets a map of all the values currently stored in the input controls. // library marker zwaveTools.parameterManagementTools, line 86
Map<Integer, BigInteger> getParameterValuesFromInputControls() // library marker zwaveTools.parameterManagementTools, line 87
{ // library marker zwaveTools.parameterManagementTools, line 88
	ConcurrentHashMap inputs = getDeviceInputs() // library marker zwaveTools.parameterManagementTools, line 89

	if (!inputs) return // library marker zwaveTools.parameterManagementTools, line 91

	Map<Integer, BigInteger> settingValues = [:] // library marker zwaveTools.parameterManagementTools, line 93

	inputs.each  // library marker zwaveTools.parameterManagementTools, line 95
		{ PKey , PData ->  // library marker zwaveTools.parameterManagementTools, line 96
			BigInteger newValue = 0 // library marker zwaveTools.parameterManagementTools, line 97
			// if the setting returns an array, then it is a bitmap control, and add together the values. // library marker zwaveTools.parameterManagementTools, line 98

			if (settings.get(PData.name as String) instanceof ArrayList) { // library marker zwaveTools.parameterManagementTools, line 100
				settings.get(PData.name as String).each{ newValue += it as BigInteger } // library marker zwaveTools.parameterManagementTools, line 101
			} else  {    // library marker zwaveTools.parameterManagementTools, line 102
				newValue = settings.get(PData.name as String) as BigInteger   // library marker zwaveTools.parameterManagementTools, line 103
			} // library marker zwaveTools.parameterManagementTools, line 104
			settingValues.put(PKey, newValue) // library marker zwaveTools.parameterManagementTools, line 105
		} // library marker zwaveTools.parameterManagementTools, line 106
	if (txtEnable) log.info "Device ${device.displayName}: Current Parameter Setting Values are: " + settingValues // library marker zwaveTools.parameterManagementTools, line 107
	return settingValues // library marker zwaveTools.parameterManagementTools, line 108
} // library marker zwaveTools.parameterManagementTools, line 109

@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allPendingParameterChanges = new ConcurrentHashMap<String, ConcurrentHashMap>(128, 0.75, 1) // library marker zwaveTools.parameterManagementTools, line 111
@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allDevicesParameterValues = new ConcurrentHashMap<String, ConcurrentHashMap>(128, 0.75, 1) // library marker zwaveTools.parameterManagementTools, line 112

ConcurrentHashMap getPendingChangeMap() { // library marker zwaveTools.parameterManagementTools, line 114
	return  allPendingParameterChanges.get(device.deviceNetworkId, new ConcurrentHashMap(32, 0.75, 1) ) // library marker zwaveTools.parameterManagementTools, line 115
} // library marker zwaveTools.parameterManagementTools, line 116

Map<Integer, BigInteger> getParameterValuesFromDevice() // library marker zwaveTools.parameterManagementTools, line 118
{ // library marker zwaveTools.parameterManagementTools, line 119
	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32, 0.75, 1)) // library marker zwaveTools.parameterManagementTools, line 120

	ConcurrentHashMap inputs = getDeviceInputs()	 // library marker zwaveTools.parameterManagementTools, line 122

	if (!inputs) return null // library marker zwaveTools.parameterManagementTools, line 124

	if ((parameterValues?.size() as Integer) == (inputs?.size() as Integer) )  { // library marker zwaveTools.parameterManagementTools, line 126
		return parameterValues // library marker zwaveTools.parameterManagementTools, line 127
	} else { // library marker zwaveTools.parameterManagementTools, line 128
		Integer waitTime = 1 // library marker zwaveTools.parameterManagementTools, line 129
		inputs.eachWithIndex  // library marker zwaveTools.parameterManagementTools, line 130
			{ k, v, i -> // library marker zwaveTools.parameterManagementTools, line 131
				if (parameterValues.get(k as Integer).is( null ) ) { // library marker zwaveTools.parameterManagementTools, line 132
					if (txtEnable) log.info "Device ${device.displayName}: Obtaining value from Zwave device for parameter # ${k}" // library marker zwaveTools.parameterManagementTools, line 133
					advancedZwaveSend(zwave.configurationV2.configurationGet(parameterNumber: k)) // library marker zwaveTools.parameterManagementTools, line 134
						// Wait 2 second for most of the reports, but wait up to 10 seconds for the last one. // library marker zwaveTools.parameterManagementTools, line 135
						waitTime = (i >= (inputs.size() -1 )) ? 10 : 2 // library marker zwaveTools.parameterManagementTools, line 136
						myReportQueue("7006").poll(waitTime, TimeUnit.SECONDS) // library marker zwaveTools.parameterManagementTools, line 137

				} else { // library marker zwaveTools.parameterManagementTools, line 139
					if (logEnable) log.debug "Device ${device.displayName}: For parameter: ${k} previously retrieved a value of ${parameterValues.get(k as Integer)}." // library marker zwaveTools.parameterManagementTools, line 140
				} // library marker zwaveTools.parameterManagementTools, line 141
			} // library marker zwaveTools.parameterManagementTools, line 142
		return parameterValues			 // library marker zwaveTools.parameterManagementTools, line 143
	} // library marker zwaveTools.parameterManagementTools, line 144
	return null // library marker zwaveTools.parameterManagementTools, line 145
} // library marker zwaveTools.parameterManagementTools, line 146

void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport  cmd) // library marker zwaveTools.parameterManagementTools, line 148
{  // library marker zwaveTools.parameterManagementTools, line 149
	log.debug "Received a configurationReport ${cmd}" // library marker zwaveTools.parameterManagementTools, line 150

	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32, 0.75, 1)) // library marker zwaveTools.parameterManagementTools, line 152
	BigInteger newValue = (cmd.size == 1) ? cmd.configurationValue[0] : cmd.scaledConfigurationValue			 // library marker zwaveTools.parameterManagementTools, line 153
	if (newValue < 0) log.warn "Device ${device.displayName}: Negative configuration value reported for configuration parameter ${cmd.parameterNumber}." // library marker zwaveTools.parameterManagementTools, line 154

	parameterValues.put((cmd.parameterNumber as Integer), newValue ) // library marker zwaveTools.parameterManagementTools, line 156

	pendingChangeMap.remove(cmd.parameterNumber as Integer) // library marker zwaveTools.parameterManagementTools, line 158

	if (txtEnable) log.info "Device ${device.displayName}: updating parameter: ${cmd.parameterNumber} to ${newValue}." // library marker zwaveTools.parameterManagementTools, line 160
	device.updateSetting("${cmd.parameterNumber}", newValue as Integer) // library marker zwaveTools.parameterManagementTools, line 161

	myReportQueue(cmd.CMD).offer( cmd ) // library marker zwaveTools.parameterManagementTools, line 163
} // library marker zwaveTools.parameterManagementTools, line 164

////////////////////////////////////////////////////////////////////// // library marker zwaveTools.parameterManagementTools, line 166
//////                  Report Queues                          /////// // library marker zwaveTools.parameterManagementTools, line 167
////////////////////////////////////////////////////////////////////// // library marker zwaveTools.parameterManagementTools, line 168
// reportQueues stores a map of SynchronousQueues. When requesting a report from a device, the report handler communicates the report back to the requesting function using a queue. This makes programming more like "synchronous" programming, rather than asynchronous handling. // library marker zwaveTools.parameterManagementTools, line 169
// This is a map within a map. The first level map is by deviceNetworkId. Since @Field static variables are shared among all devices using the same driver code, this ensures that you get a unique second-level map for a particular device. The second map is keyed by the report class hex string. For example, if you want to wait for the configurationGet report, wait for "7006". // library marker zwaveTools.parameterManagementTools, line 170
@Field static reportQueues = new ConcurrentHashMap<String, ConcurrentHashMap>(128, 0.75, 1) // library marker zwaveTools.parameterManagementTools, line 171

SynchronousQueue myReportQueue(String reportClass) { // library marker zwaveTools.parameterManagementTools, line 173
	ConcurrentHashMap thisDeviceQueues = reportQueues.get(device.deviceNetworkId, new ConcurrentHashMap<String,SynchronousQueue>(32, 0.75, 1)) // library marker zwaveTools.parameterManagementTools, line 174

	// Get the queue if it exists, create (new) it if it does not. // library marker zwaveTools.parameterManagementTools, line 176
	SynchronousQueue thisReportQueue = thisDeviceQueues.get(reportClass, new SynchronousQueue()) // library marker zwaveTools.parameterManagementTools, line 177
	return thisReportQueue // library marker zwaveTools.parameterManagementTools, line 178
} // library marker zwaveTools.parameterManagementTools, line 179

// ~~~~~ end include (165) zwaveTools.parameterManagementTools ~~~~~
