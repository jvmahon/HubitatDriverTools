library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Tools for Interacting with the OpenSmartHouse.org database",
        name: "openSmarthouseTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "",
		librarySource:""
)

Map reparseDeviceData(deviceData = null )
{
	// When data is stored in the state.deviceRecord it can lose its original data types, so need to restore after reading the data froms state.
	// This is only done during the startup / initialize routine and results are stored in a global variable, so it is only done for the first device of a particular model.

	if (deviceData.is( null )) return null
	Map reparsed = [formatVersion: null , fingerprints: null , classVersions: null ,endpoints: null , deviceInputs: null ]

	reparsed.formatVersion = deviceData.formatVersion as Integer
	
	if (deviceData.endpoints) {
		reparsed.endpoints = deviceData.endpoints.collectEntries{k, v -> [(k as Integer), (v)] }
	} else {
		List<Integer> endpoint0Classes = getDataValue("inClusters")?.split(",").collect{ hexStrToUnsignedInt(it) as Integer }
						endpoint0Classes += getDataValue("secureInClusters")?.split(",").collect{ hexStrToUnsignedInt(it) as Integer }
		if (endpoint0Classes.contains(0x60))
			{
				log.error "Device ${device.displayName}: Error in function reparseDeviceData. Missing endpoint data for a multi-endpoint device. This usually occurs if there is a locally stored data record which does not properly specify the endpoint data. This device may still function, but only for the root device."
			}
		reparsed.endpoints = [0:[classes:(endpoint0Classes)]]
	}
	
	reparsed.deviceInputs = deviceData.deviceInputs?.collectEntries{ k, v -> [(k as Integer), (v)] }
	reparsed.fingerprints = deviceData.fingerprints?.collect{ it -> [manufacturer:(it.manufacturer as Integer), deviceId:(it.deviceId as Integer),  deviceType:(it.deviceType as Integer), name:(it.name)] }
	if (deviceData.classVersions) reparsed.classVersions = deviceData.classVersions?.collectEntries{ k, v -> [(k as Integer), (v as Integer)] }
	if (logEnable) "Device ${device.displayName}: Reparsed data is ${reparsed}"
	return reparsed
}


Map getSpecificRecord(id)
{
    String queryByDatabaseID= "http://www.opensmarthouse.org/dmxConnect/api/zwavedatabase/device/read.php?device_id=${id}"    
    
	httpGet([uri:queryByDatabaseID]) { resp-> 
				return resp?.data
			}
}

Map OpenSmarthouseCreateDeviceDataRecord()
{
	Map firstQueryRecord = getOpenSmartHouseData();
	
	if (firstQueryRecord.is( null )) {
	log.error "Device ${device.displayName}: Failed to retrieve data record identifier for device from OpenSmartHouse Z-Wave database. OpenSmartHouse database may be unavailable. Try again later or check database to see if your device can be found in the database."
	}

	Map thisRecord = getSpecificRecord(firstQueryRecord.id)
	
	if (thisRecord.is( null )) {
	log.error "Device ${device.displayName}: Failed to retrieve data record for device from OpenSmartHouse Z-Wave database. OpenSmartHouse database may be unavailable. Try again later or check database to see if your device can be found in the database."
	}

	Map deviceRecord = [fingerprints: [] , endpoints: [:] , deviceInputs: null ]
	Map thisFingerprint = [manufacturer: (getDataValue("manufacturer")?.toInteger()) , deviceId: (getDataValue("deviceId")?.toInteger()) ,  deviceType: (getDataValue("deviceType")?.toInteger()) ]
	thisFingerprint.name = "${firstQueryRecord.manufacturer_name}: ${firstQueryRecord.label}" as String
	
	deviceRecord.fingerprints.add(thisFingerprint )
	
	deviceRecord.deviceInputs = createInputControls(thisRecord.parameters)
	deviceRecord.classVersions = getRootClassData(thisRecord.endpoints)
	deviceRecord.endpoints = getEndpointClassData(thisRecord.endpoints)
	deviceRecord.formatVersion = dataRecordFormatVersion
	
	return deviceRecord
}

// List getOpenSmartHouseData()
Map getOpenSmartHouseData()
{
	if (txtEnable) log.info "Getting data from OpenSmartHouse for device ${device.displayName}."
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2)
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2)
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2)

    String DeviceInfoURI = "http://www.opensmarthouse.org/dmxConnect/api/zwavedatabase/device/list.php?filter=manufacturer:0x${manufacturer}%20${deviceType}:${deviceID}"

    Map thisDeviceData
			
    httpGet([uri:DeviceInfoURI])
    { 
		resp ->
		Map maxRecord = resp.data.devices.max(			{ a, b -> 
				List<Integer> a_version = a.version_max.split("\\.")
				List<Integer> b_version = b.version_max.split("\\.")
			
				Float a_value = a_version[0].toFloat() + (a_version[1].toFloat() / 1000)
				Float b_value = b_version[0].toFloat() + (b_version[1].toFloat() / 1000)
				
				(a_value <=> b_value)
			})
		return maxRecord
	}
}

Map getRootClassData(endpointRecord) {
	endpointRecord.find{ it.number == 0}.commandclass.collectEntries{thisClass ->
					[(classMappings.get(thisClass.commandclass_name, 0) as Integer), (thisClass.version as Integer)]
				}
}

String getChildComponentDriver(List classes)
{
	if (classes.contains(0x25) ){  // Binary Switch
		if (classes.contains(0x32) ){ // Meter Supported
			return "Generic Component Metering Switch"
		} else {
			return "Generic Component Switch"
		}
	} else  if (classes.contains(0x26)){ // MultiLevel Switch
		if (classes.contains(0x32) ){ // Meter Supported
			return "Generic Component Metering Dimmer"
		} else {
			return "Generic Component Dimmer"
		}			
	}
	return "Generic Component Dimmer"
}

Map getEndpointClassData(endpointRecord)
{
	Map endpointClassMap = [:]

	endpointRecord.each{ it ->
			List thisEndpointClasses =  it.commandclass.collect{thisClass -> classMappings.get(thisClass.commandclass_name, 0) as Integer }
			
			if (it.number == 0) {
				endpointClassMap.put((it.number as Integer), [classes:(thisEndpointClasses)])
				return
			} else {
				String childDriver = getChildComponentDriver(thisEndpointClasses)
				endpointClassMap.put((it.number as Integer), [driver:[type:childDriver, namespace:"hubitat"], classes:(thisEndpointClasses)])
			}
		}
    return endpointClassMap
}

Map createInputControls(data)
{
	if (!data) return null
	
	Map inputControls = [:]	
	data?.each
	{
		if (it.read_only as Integer) {
				log.info "Device ${device.displayName}: Parameter ${it.param_id}-${it.label} is read-only. No input control created."
				return
			}
	
		if (it.bitmask.toInteger())
		{
			if (!(inputControls?.get(it.param_id)))
			{
				log.warn "Device ${device.displayName}: Parameter ${it.param_id} is a bitmap field. This is poorly supported. Treating as an integer - rely on your user manual for proper values!"
				String param_name_string = "${it.param_id}"
				String title_string = "(${it.param_id}) ${it.label} - bitmap"
				Map newInput = [name:param_name_string , type:"number", title: title_string, size:it.size]
				if ((it.description.size() != 0) && (it.description != it.label)) newInput.description = it.description
				
				inputControls.put(it.param_id, newInput)
			}
		} else {
			String param_name_string = "${it.param_id}"
			String title_string = "(${it.param_id}) ${it.label}"
			
			Map newInput = [name: param_name_string, title: title_string, size:it.size]
			if ((it.description.size() != 0) && (it.description != it.label)) newInput.description = it.description

			def deviceOptions = [:]
			it.options.each { deviceOptions.put(it.value, it.label) }
			
			// Set input type. Should be one of: bool, date, decimal, email, enum, number, password, time, text. See: https://docs.hubitat.com/index.php?title=Device_Preferences
			
			// use enum but only if it covers all of the choices!
			Integer numberOfValues = (it.maximum - it.minimum) +1
			if (deviceOptions && (deviceOptions.size() == numberOfValues) )
			{
				newInput.type = "enum"
				newInput.options = deviceOptions
			} else {
				newInput.type = "number"
				newInput.range = "${it.minimum}..${it.maximum}"
			}
			inputControls[it.param_id] = newInput
		}
	}
	return inputControls
}

@Field static Map classMappings = [
	COMMAND_CLASS_ALARM:0x71,
	COMMAND_CLASS_SENSOR_ALARM :0x9C,
	COMMAND_CLASS_SILENCE_ALARM:0x9D,
	COMMAND_CLASS_SWITCH_ALL:0x27,
	COMMAND_CLASS_ANTITHEFT:0x5D,
	COMMAND_CLASS_ANTITHEFT_UNLOCK:0x7E,
	COMMAND_CLASS_APPLICATION_CAPABILITY:0x57,
	COMMAND_CLASS_APPLICATION_STATUS:0x22,
	COMMAND_CLASS_ASSOCIATION:0x85,
	COMMAND_CLASS_ASSOCIATION_COMMAND_CONFIGURATION:0x9B,
	COMMAND_CLASS_ASSOCIATION_GRP_INFO:0x59,
	COMMAND_CLASS_AUTHENTICATION:0xA1,
	COMMAND_CLASS_AUTHENTICATION_MEDIA_WRITE:0xA2,
	COMMAND_CLASS_BARRIER_OPERATOR:0x66,
	COMMAND_CLASS_BASIC:0x20,
	COMMAND_CLASS_BASIC_TARIFF_INFO:0x36,
	COMMAND_CLASS_BASIC_WINDOW_COVERING:0x50,
	COMMAND_CLASS_BATTERY:0x80,
	COMMAND_CLASS_SENSOR_BINARY:0x30,
	COMMAND_CLASS_SWITCH_BINARY:0x25,
	COMMAND_CLASS_SWITCH_TOGGLE_BINARY:0x28,
	COMMAND_CLASS_CLIMATE_CONTROL_SCHEDULE:0x46,
	COMMAND_CLASS_CENTRAL_SCENE:0x5B,
	COMMAND_CLASS_CLOCK:0x81,
	COMMAND_CLASS_SWITCH_COLOR:0x33,
	COMMAND_CLASS_CONFIGURATION:0x70,
	COMMAND_CLASS_CONTROLLER_REPLICATION:0x21,
	COMMAND_CLASS_CRC_16_ENCAP:0x56,
	COMMAND_CLASS_DCP_CONFIG:0x3A,
	COMMAND_CLASS_DCP_MONITOR:0x3B,
	COMMAND_CLASS_DEVICE_RESET_LOCALLY:0x5A,
	COMMAND_CLASS_DOOR_LOCK:0x62,
	COMMAND_CLASS_DOOR_LOCK_LOGGING:0x4C,
	COMMAND_CLASS_ENERGY_PRODUCTION:0x90,
	COMMAND_CLASS_ENTRY_CONTROL :0x6F,
	COMMAND_CLASS_FIRMWARE_UPDATE_MD:0x7A,
	COMMAND_CLASS_GENERIC_SCHEDULE:0xA3,
	COMMAND_CLASS_GEOGRAPHIC_LOCATION:0x8C,
	COMMAND_CLASS_GROUPING_NAME:0x7B,
	COMMAND_CLASS_HAIL:0x82,
	COMMAND_CLASS_HRV_STATUS:0x37,
	COMMAND_CLASS_HRV_CONTROL:0x39,
	COMMAND_CLASS_HUMIDITY_CONTROL_MODE:0x6D,
	COMMAND_CLASS_HUMIDITY_CONTROL_OPERATING_STATE:0x6E,
	COMMAND_CLASS_HUMIDITY_CONTROL_SETPOINT:0x64,
	COMMAND_CLASS_INCLUSION_CONTROLLER:0x74,
	COMMAND_CLASS_INDICATOR:0x87,
	COMMAND_CLASS_IP_ASSOCIATION:0x5C,
	COMMAND_CLASS_IP_CONFIGURATION:0x9A,
	COMMAND_CLASS_IR_REPEATER:0xA0,
	COMMAND_CLASS_IRRIGATION:0x6B,
	COMMAND_CLASS_LANGUAGE:0x89,
	COMMAND_CLASS_LOCK:0x76,
	COMMAND_CLASS_MAILBOX:0x69,
	COMMAND_CLASS_MANUFACTURER_PROPRIETARY:0x91,
	COMMAND_CLASS_MANUFACTURER_SPECIFIC:0x72,
	COMMAND_CLASS_MARK:0xEF,
	COMMAND_CLASS_METER:0x32,
	COMMAND_CLASS_METER_TBL_CONFIG:0x3C,
	COMMAND_CLASS_METER_TBL_MONITOR:0x3D,
	COMMAND_CLASS_METER_TBL_PUSH:0x3E,
	COMMAND_CLASS_MTP_WINDOW_COVERING:0x51,
	COMMAND_CLASS_MULTI_CHANNEL:0x60,
	COMMAND_CLASS_MULTI_CHANNEL_ASSOCIATION:0x8E,
	COMMAND_CLASS_MULTI_CMD:0x8F,
	COMMAND_CLASS_SENSOR_MULTILEVEL:0x31,
	COMMAND_CLASS_SWITCH_MULTILEVEL:0x26,
	COMMAND_CLASS_SWITCH_TOGGLE_MULTILEVEL:0x29,
	COMMAND_CLASS_NETWORK_MANAGEMENT_BASIC:0x4D,
	COMMAND_CLASS_NETWORK_MANAGEMENT_INCLUSION:0x34,
	NETWORK_MANAGEMENT_INSTALLATION_MAINTENANCE:0x67,
	COMMAND_CLASS_NETWORK_MANAGEMENT_PRIMARY:0x54,
	COMMAND_CLASS_NETWORK_MANAGEMENT_PROXY:0x52,
	COMMAND_CLASS_NO_OPERATION:0x00,
	COMMAND_CLASS_NODE_NAMING:0x77,
	COMMAND_CLASS_NODE_PROVISIONING:0x78,
	COMMAND_CLASS_NOTIFICATION:0x71,
	COMMAND_CLASS_POWERLEVEL:0x73,
	COMMAND_CLASS_PREPAYMENT:0x3F,
	COMMAND_CLASS_PREPAYMENT_ENCAPSULATION:0x41,
	COMMAND_CLASS_PROPRIETARY:0x88,
	COMMAND_CLASS_PROTECTION:0x75,
	COMMAND_CLASS_METER_PULSE:0x35,
	COMMAND_CLASS_RATE_TBL_CONFIG:0x48,
	COMMAND_CLASS_RATE_TBL_MONITOR:0x49,
	COMMAND_CLASS_REMOTE_ASSOCIATION_ACTIVATE:0x7C,
	COMMAND_CLASS_REMOTE_ASSOCIATION:0x7D,
	COMMAND_CLASS_SCENE_ACTIVATION:0x2B,
	COMMAND_CLASS_SCENE_ACTUATOR_CONF:0x2C,
	COMMAND_CLASS_SCENE_CONTROLLER_CONF:0x2D,
	COMMAND_CLASS_SCHEDULE:0x53,
	COMMAND_CLASS_SCHEDULE_ENTRY_LOCK:0x4E,
	COMMAND_CLASS_SCREEN_ATTRIBUTES:0x93,
	COMMAND_CLASS_SCREEN_MD:0x92,
	COMMAND_CLASS_SECURITY:0x98,
	COMMAND_CLASS_SECURITY_2:0x9F,
	COMMAND_CLASS_SECURITY_SCHEME0_MARK :0xF100,
	COMMAND_CLASS_SENSOR_CONFIGURATION:0x9E,
	COMMAND_CLASS_SIMPLE_AV_CONTROL:0x94,
	COMMAND_CLASS_SOUND_SWITCH:0x79,
	COMMAND_CLASS_SUPERVISION:0x6C,
	COMMAND_CLASS_TARIFF_CONFIG:0x4A,
	COMMAND_CLASS_TARIFF_TBL_MONITOR:0x4B,
	COMMAND_CLASS_THERMOSTAT_FAN_MODE:0x44,
	COMMAND_CLASS_THERMOSTAT_FAN_STATE:0x45,
	COMMAND_CLASS_THERMOSTAT_MODE:0x40,
	COMMAND_CLASS_THERMOSTAT_OPERATING_STATE:0x42,
	COMMAND_CLASS_THERMOSTAT_SETBACK:0x47,
	COMMAND_CLASS_THERMOSTAT_SETPOINT:0x43,
	COMMAND_CLASS_TIME:0x8A,
	COMMAND_CLASS_TIME_PARAMETERS:0x8B,
	COMMAND_CLASS_TRANSPORT_SERVICE:0x55,
	COMMAND_CLASS_USER_CODE:0x63,
	COMMAND_CLASS_VERSION:0x86,
	COMMAND_CLASS_WAKE_UP:0x84,
	COMMAND_CLASS_WINDOW_COVERING:0x6A,
	COMMAND_CLASS_ZIP:0x23,
	COMMAND_CLASS_ZIP_6LOWPAN:0x4F,
	COMMAND_CLASS_ZIP_GATEWAY:0x5F,
	COMMAND_CLASS_ZIP_NAMING:0x68,
	COMMAND_CLASS_ZIP_ND:0x58,
	COMMAND_CLASS_ZIP_PORTAL:0x61,
	COMMAND_CLASS_ZWAVEPLUS_INFO:0x5E,
]