

metadata {
	definition (name: "New Libraried Driver v0.0.1",namespace: "jvm", author: "jvm") {
		// capability "Configure"
		capability "Initialize"
		capability "Refresh"

		capability "Actuator"
		capability "Switch"
		capability "SwitchLevel"
		capability "ChangeLevel"
		
        // capability "Sensor"				
        // capability "MotionSensor"
        // capability "TamperAlert"
		// capability "WaterSensor"
		// capability "ContactSensor"
		// capability "ShockSensor"		// Use this for glass breakage!
		// capability "IllumanceMeasurement"
		// capability "LiquidFlowRate"
		// attribute "carbonDioxideDetected"
		
		// capability "EnergyMeter"
        // capability "PowerMeter"
		// capability "VoltageMeasurement"
        // capability "CurrentMeter"
		// attribute "energyConsumed", "number" 	// Custom Attribute for meter devices supporting energy consumption. Comment out if not wanted.
		// attribute "powerFactor", "number"	// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		// attribute "pulseCount", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		// attribute "reactiveCurrent", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		// attribute "reactivePower", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		
		// capability "Battery"

		// capability "Consumable" 		// For smoke, CO, CO2 alarms that report their end-of-life
		// capability "FilterStatus" 	// For water filters that report status of filter
		
		capability "PushableButton"
		capability "HoldableButton"
		capability "ReleasableButton"
		capability "DoubleTapableButton"	
		attribute "multiTapButton", "number"

		command "identify" // implements the Z-Wave Plus identify function which can flash device indicators.
		command "resetDriver" // deletes the stored state information
							
        command "multiTap", [[name:"button",type:"NUMBER", description:"Button Number", constraints:["NUMBER"]],
					[name:"taps",type:"NUMBER", description:"Tap count", constraints:["NUMBER"]]]	

		command "setParameter",[[name:"parameterNumber",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]],
					[name:"value",type:"NUMBER", description:"Parameter Value", constraints:["NUMBER"]]
					]	

		// Following Command is to help create a new data record to be added to deviceDatabase
        command "logDataRecord"

    }
	
	preferences 
	{	
        input name: "showParameterInputs", type: "bool", title: "Show Parameter Value Input Controls", defaultValue: false  
        input name: "alwaysSendCCEvent", type: "bool", title: "Always Send Central Scene Events to Child Devices", defaultValue: false    
		
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
		input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true
		if (showParameterInputs) {
			getParameterValuesFromDevice()
			deviceInputs?.each{key, value -> input value}
        }
    }	
}
#import zwaveTools.globalDataTools

void identify() {
	log.warn "Device ${device.displayName}: The 'identify' function is experimental and only works for Zwave Plus Version 2 or greater devices!"
	// Identify function supported by Zwave Plus Version 2 and greater devices!
		List<Map<String, Short>> indicators = [
			[indicatorId:0x50, propertyId:0x03, value:0x08], 
			[indicatorId:0x50, propertyId:0x04, value:0x03],  
			[indicatorId:0x50, propertyId:0x05, value:0x06]
		]
		sendUnsupervised(zwave.indicatorV3.indicatorSet(indicatorCount:3 , value:0, indicatorValues: indicators ))
}


ConcurrentHashMap getDataRecordByProduct()
{
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2)
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2)
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2)
	String productKey = "${manufacturer}:${deviceType}:${deviceID}"
	return globalDataStorage.get(productKey, new ConcurrentHashMap())
}

void showglobalDataRecord() {
	ConcurrentHashMap dataRecord = getDataRecordByProduct()
	log.info "Data record in global storage is ${dataRecord}."
}


void resetDriver() { state.clear() }


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

void configure()
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
	
	Map localDataRecord = getLocallyStoredDataRecord()
	if (localDataRecord && (localDataRecord.formatVersion != dataRecordFormatVersion)) {
		log.warn "Device ${device.displayName}: Locally stored data record has wrong version number and will be ignored. Obtaining data from openSmartHouse instead. Locally stored record is: ${localDataRecord.inspect()}."
		}
		
	if (localDataRecord && (localDataRecord.formatVersion == dataRecordFormatVersion)){
		state.remove("deviceRecord") // If a device data record was added to the database, delete if it was previously from openSmartHouse.
		dataRecordByProduct.deviceRecord = reparseDeviceData(localDataRecord) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord && dataRecordByProduct.deviceRecord.is( null ) ) { 
		// Put in the Global ConcurrentHashMap if it exist locally.
		dataRecordByProduct.deviceRecord = reparseDeviceData(state.deviceRecord) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord.is( null ) && dataRecordByProduct.deviceRecord ) {
		// Data record doesn't exist in state, but it is in the concurrentHashMap - So store in state rather than re-retrieve
		state.deviceRecord = dataRecordByProduct.deviceRecord
	} else if ( state.deviceRecord.is( null )) {
		// Data record doesn't exist - get it and store in the global data record
		Map createdRecord = OpenSmarthouseCreateDeviceDataRecord() 
		state.deviceRecord = createdRecord
		if (createdRecord) dataRecordByProduct.deviceRecord = reparseDeviceData(createdRecord)
	}
	///////////////////////////////////////////////////////////////////////////////////
	//////////          Done with Device Data Record Management      //////////////////
	///////////////////////////////////////////////////////////////////////////////////	
	
	// Create child devices if this is a multi-channel device or otherwise specified in the device record.
	if ( state.configured == false ) {
		deleteUnwantedChildDevices()
		createChildDevices()
		state.configured = true 
	}
}
void initialize()
{
	configure()
	state.configured = true 

	if (getThisDeviceDataRecord().classVersions?.containsKey(0x5B)) sendUnsupervised(zwave.centralSceneV3.centralSceneSupportedGet())
	if (getThisDeviceDataRecord().classVersions?.containsKey(0x6C)) sendInitialCommand()
	
	if (txtEnable) log.info "Device ${device.displayName}: Refreshing device data."
	refresh()  
	
	if (txtEnable) log.info "Device ${device.displayName}: Done Initializing."

}


//////////// Get Inputs //////////////
Map getThisDeviceDataRecord(){
	dataRecordByProduct?.deviceRecord
}

Map getDeviceInputs()  { 
	Map returnMe = dataRecordByProduct?.deviceRecord?.deviceInputs.sort({it.key})
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
	
	Map record = thisDeviceDataRecord.get("endpoints").get(ep)
		if (logEnable) log.debug "Device ${device.displayName}: Refreshing endpoint: ${ep ?: 0} with record ${record}"
		if (txtEnable) log.info "Device ${device.displayName}: refreshing values for endpoint ${ep}."
		
		if (record.classes.contains(0x25) || record.classes.contains(0x26)) binaryAndMultiLevelDeviceTools_refresh(ep)
		if (record.classes.contains(0x32)) 		meterTools_refreshMeters(ep)
		if (record.classes.contains(0x71)) 		notificationTools_refresh(ep)
		// if (record.classes.contains(0x62)) 	lockTools_refresh(ep)
		if (record.classes.contains(0x80)) 		batteryTools_refresh()
		// if (record.classes.contains()) 		sensorTools_refresh()
}

void refresh()
{
	thisDeviceDataRecord.get("endpoints").each{thisEp, v ->
		refreshEndpoint(ep:thisEp)
	}
}

void	refreshLock(ep = null ) {
	log.error "Device ${device.displayName} Function refreshLock is not fully implemented."
}



/////////////////////////////////////////////////////////////////////////////////////// 
///////                   Parameter Updating and Management                    ////////
///////      Handle Update(), and Set, Get, and Process Parameter Values       ////////
/////////////////////////////////////////////////////////////////////////////////////// 

void logsOff() {
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void updated()
{
	if (txtEnable) log.info "Device ${device.displayName}: Updating changed parameters (if any) . . ."
	if (logEnable) runIn(1800,logsOff)
	
	ConcurrentHashMap<Integer, BigInteger> parameterValueMap = getParameterValuesFromDevice()
	if (parameterValueMap.is( null )) {
			log.error "Device ${device.displayName}: In function updated(), failed to retrieve parameter values from Device."
			return
		}

	ConcurrentHashMap<Integer, BigInteger> pendingChanges = getPendingChangeMap()

	Map<Integer, BigInteger>  settingValueMap = getParameterValuesFromInputControls()
	// if (logEnable ) log.debug "Device ${device.displayName}: Current input control values are: ${settingValueMap}"

	// Find what changed
	settingValueMap.findAll{k, v -> !(v.is( null ))}.each {k, v ->
			Boolean changedValue = ((v as BigInteger) != (parameterValueMap.get(k as Integer) as BigInteger)) 
			if (changedValue) {
				pendingChanges?.put(k as Integer, v as BigInteger)
			} else pendingChanges?.remove(k)
		}

	if (txtEnable) log.info "Device ${device.displayName}: Pending parameter changes are: ${pendingChanges ?: "None"}"
	
	if (isZwaveListening()) processPendingChanges()
	if (txtEnable) log.info "Device ${device.displayName}: Done updating changed parameters (if any) . . ."

}

//////////////////////////////////////////////////////////////////////
//////                  Z-Wave Helper Functions                ///////
//////   Format messages, Send to Device, secure Messages      ///////
//////////////////////////////////////////////////////////////////////
Map getDefaultParseMap() {
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



//////////////////////////////////////////////////////////////////////
//////        Handle Binary and MultiLevel Devices        ///////
////////////////////////////////////////////////////////////////////// 
#include zwaveTools.binaryAndMultiLevelDeviceTools

////////////////////////////////////////////////////////////
//////              Endpoint Handling          ///////
////////////////////////////////////////////////////////////
#include zwaveTools.endpointTools

////////////////////////////////////////////////////////////
//////              Handle   Notifications           ///////
////////////////////////////////////////////////////////////
#include zwaveTools.notificationTools

///////////////////////////////////////////////////////////
//////     Handle Binary and MultiLevel Sensors     ///////
///////////////////////////////////////////////////////////
#include zwaveTools.sensorTools

//////////////////////////////////////////////////////////
//////             Handle  Meters                  ///////
//////////////////////////////////////////////////////////
#include zwaveTools.meterTools

//////////////////////////////////////////////////////////////////////
//////        Handle Battery Reports and Device Functions        ///////
////////////////////////////////////////////////////////////////////// 
#include zwaveTools.batteryTools

//////////////////////////////////////////////////////////
/////////       Central Scene Processing          ////////
//////////////////////////////////////////////////////////
#include zwaveTools.centralSceneTools

//////////////////////////////////////////////////////////////////////
////  Handle Hub Interactions, Sending, Parsing Supervision   ///////
////////////////////////////////////////////////////////////////////// 
#include zwaveTools.hubTools

///////////////////////////////////////////////////////////////
//////      OpenSmartHouse Database Interactions           ///////
///////////////////////////////////////////////////////////////
#include zwaveTools.openSmarthouseTools

void logDataRecord() {
	log.info "Data record is: \n${thisDeviceDataRecord.inspect()}"
}

///////////////////////////////////////////////////////////////
//////      Database of Device Characteristics           ///////
///////////////////////////////////////////////////////////////
#include zwaveTools.deviceInfoAndManagementTools
