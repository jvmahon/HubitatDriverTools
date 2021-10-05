import java.util.concurrent.* // Available (allow-listed) concurrency classes: ConcurrentHashMap, ConcurrentLinkedQueue, Semaphore, SynchronousQueue
import groovy.transform.Field

//////////////
#include zwaveTools.sendReceiveTools
void supervisionCheckResendInfo(report){
	log.warn "Device ${device.displayName}: Supervision Check is resending command: ${report.command} for endpoint ${report.endPoint}, previous attempts: ${report.attempt}."
}
Integer getS2MaxRetries() { return 5 }
Integer getS2RetryPeriod() { return 1500}
/////////// 
#include zwaveTools.globalDataTools
#include zwaveTools.endpointTools
#include zwaveTools.batteryTools
#include zwaveTools.zwaveDeviceDatabase
#include zwaveTools.notificationTools
#include zwaveTools.meterTools
#include zwaveTools.sensorTools
#include zwaveTools.binaryAndMultilevelDeviceTools
#include zwaveTools.centralSceneTools
#include zwaveTools.openSmarthouseTools
#include zwaveTools.childDeviceTools
#include zwaveTools.parameterManagementTools
/////////////////


metadata {
	definition (name: "Any Z-Wave Universal Parent Driver v1.6",namespace: "jvm", author: "jvm", singleThreaded:false) {
		capability "Initialize"
		capability "Refresh"

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
