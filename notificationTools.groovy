library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handles Zwave Notifications",
        name: "notificationTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/notificationTools.groovy"
)
//////////////////////////////////////////////////////////////////////
//////        Handle   Notifications     ///////
//////////////////////////////////////////////////////////////////////

void	notificationTools_refresh(ep = null ) {
	Map specifiedNotifications = thisDeviceDataRecord?.endpoints.get((ep ?: 0) as Integer)?.get("notifications")
	log.debug "specifiedNotifications are ${specifiedNotifications}"
	if (specifiedNotifications)
	{ 
		log.debug "using the specifiedNotifications to refresh"

		specifiedNotifications.each{type, events ->
				performRefresh(type, events, ep)
				}
	}	else  {
			log.debug "learning the Notifications to refresh"

			sendUnsupervised(zwave.notificationV8.notificationSupportedGet(), ep)
	}
}

void performRefresh(type, events, ep)
{
	events.each{ it ->
		sendUnsupervised(zwave.notificationV8.notificationGet(v1AlarmType:0, event: (it as Integer), notificationType: type), ep)
	}
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationSupportedReport report, ep = null )
{ 
	if (logEnable) {
		log.debug "Device ${device.displayName}: Received a NotificationSupportedReport: ${report} for endpoint ${ep ?: 0}."
	}
	
	List<Integer> notificationTypes = []
	if (report.smoke)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:1), ep) } // Smoke
	if (report.co)					{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:2), ep) }  // CO
	if (report.co2)					{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:3), ep) }  // CO2
	if (report.heat)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:4), ep) }  // Heat
	if (report.water)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:5), ep) }  // Water
	if (report.accessControl) 		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:6), ep) }  // Access Control
	if (report.burglar)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:7), ep) }  // Burglar
	if (report.powerManagement)		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:8), ep) }  // Power Management
	if (report.system)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:9), ep) }  // System
	if (report.emergency)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:10), ep) }  // Emergency Alarm
	if (report.clock)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:11), ep) }  // Clock
	if (report.appliance)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:12), ep) } // Appliance
	if (report.homeHealth)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:13), ep) }  // Home Health
	if (report.siren)				{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:14), ep) }  // Siren
	if (report.waterValve)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:15), ep) }  // Water Valve
	if (report.weatherAlarm)		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:16), ep) }  // Weather Alarm
	if (report.irrigation)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:17), ep) } // Irrigation
	if (report.gasAlarm)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:18), ep) } // Gas Alarm
	if (report.pestControl)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:19), ep) }  // Pest Control
	if (report.lightSensor)			{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:20), ep) } // Light Sensor
	if (report.waterQuality)		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:21), ep) }  // Water Quality
	if (report.homeMonitoring)		{ sendUnsupervised(zwave.notificationV8.eventSupportedGet(notificationType:22), ep) }  // Home Monitoring
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.EventSupportedReport cmd, ep = null )
{
	// Build a map of the notifications supported by a device endpoint and store it in the endpoint data
	List supportedEventsByType = cmd.supportedEvents.findAll{k, v -> ((v as Boolean) == true) }.collect{k, v -> (k as Integer) }
	
	Map thisEndpointNotifications = thisDeviceDataRecord?.endpoints.get((ep ?: 0) as Integer).get("notifications", [:])
		
	thisEndpointNotifications.put( (cmd.notificationType as Integer), supportedEventsByType)
}

Map getFormattedZWaveNotificationEvent(def cmd)
{
	Map notificationEvent =
		[ 	1:[ // Smoke
				0:[	
					1:[name:"smoke" , value:"clear", descriptionText:"Smoke detected (location provided) status Idle."],
					2:[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."],
					4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					7:[name:"consumableStatus" , value:"good", descriptionText:"Periodic Maintenance Not Due"],				
					8:[name:"consumableStatus" , value:"good", descriptionText:"No Dust in device - clear."],
					], 
				1:[name:"smoke" , value:"detected", descriptionText:"Smoke detected (location provided)."], 
				2:[name:"smoke" , value:"detected", descriptionText:"Smoke detected."],
				4:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required."],				
				5:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."],				
				7:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."],				
				8:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."],
				],
			2:[ // CO
				0:[
					1:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],
					2:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],	
					4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					7:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance required cleared, periodic inspection."],				
					], 
				1:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected (location provided)."], 
				2:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."]
				],
			2:[ // CO2
				0:[
					1:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],
					2:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],	
					4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
					7:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance required cleared, periodic inspection."],				
					], 
				1:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected (location provided)."], 
				2:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."]
				],					
			5:[ // Water
				0:[
					1:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
					2:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
					5:[name:"filterStatus " , value:"normal", descriptionText:"Water filter good."],				

				], 
				1:[name:"water" , value:"wet", descriptionText:"Water leak detected (location provided)."], 
				2:[name:"water" , value:"wet", descriptionText:"Water leak detected."],
				5:[name:"filterStatus " , value:"replace", descriptionText:"Replace water filter (End-of-Life)."],				

				],
			6:[ // Access Control (Locks)
				0:[], 
				1:[name:"lock" , value:"locked", descriptionText:"Manual lock operation"], 
				2:[name:"lock" , value:"unlocked", descriptionText:"Manual unlock operation"], 
				3:[name:"lock" , value:"locked", descriptionText:"RF lock operation"], 
				4:[name:"lock" , value:"unlocked", descriptionText:"RF unlock operation"], 
				5:[name:"lock" , value:"locked", descriptionText:"Keypad lock operation"], 
				6:[name:"lock" , value:"unlocked", descriptionText:"Keypad unlock operation"], 
				11:[name:"lock" , value:"unknown", descriptionText:"Lock jammed"], 				
				254:[name:"lock" , value:"unknown", descriptionText:"Lock in unknown state"]
				],
			7:[ // Home Security
				0:[	 // These events "clear" a sensor.	
						1:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed (location provided)"], 
						2:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed"], 					
						3:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
						4:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
						5:[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected (location provided)"], // glass Breakage  attribute!
						6:[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected"], 	 // glass Breakage attribute!					
						7:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."],
						8:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."],
						9:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
						
					], 
				1:[name:"contact" , value:"open", descriptionText:"Contact sensor, open (location provided)"], 	
				2:[name:"contact" , value:"open", descriptionText:"Contact sensor, open"], 					
				3:[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"], 
				4:[name:"tamper" , value:"detected", descriptionText:"Tampering, invalid code."], 
				5:[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected (location provided)"], 
				6:[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected"], 				
				7:[name:"motion" , value:"active", descriptionText:"Motion detected (location provided)."],
				8:[name:"motion" , value:"active", descriptionText:"Motion detected."],
				9:[name:"tamper" , value:"detected", descriptionText:"Tampering, device moved"]
				],
			14:[ // Siren
				0:[
					1:[name:"alarm" , value:"off", descriptionText:"Alarm Siren Off."]
					], 
				1:[name:"alarm" , value:"siren", descriptionText:"Alarm Siren On."]
				], 
			15:[ // Water Valve
				0:[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Valve Operation."], 
				1:[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Master Valve Operation."] 
				], 
			18:[ // Gas Detector
				0:[		1:[name:"naturalGas" , value:"clear", descriptionText:"Combustible Gas state cleared (location provided)."],
						2:[name:"naturalGas" , value:"clear", descriptionText:"Combustible Gas state cleared."],
						3:[name:"naturalGas" , value:"clear", descriptionText:"Toxic gas state cleared (location provided)."],
						4:[name:"naturalGas" , value:"clear", descriptionText:"Toxic gas state cleared."] 
					], 
				1:[name:"naturalGas" , value:"detected", descriptionText:"Combustible Gas Detected (location provided)"], 
				2:[name:"naturalGas" , value:"detected", descriptionText:"Combustible Gas Detected"], 
				3:[name:"naturalGas" , value:"detected", descriptionText:"Toxic Gas detected (location provided)."],
				4:[name:"naturalGas" , value:"detected", descriptionText:"Toxic Gas detected."]
				],				
			22:[ // Presence
				0:[
					1:[name:"presence" , value:"not present", descriptionText:"Home not occupied"],
					2:[name:"presence" , value:"not present", descriptionText:"Home not occupied"]
					], 
				1:[name:"presence" , value:"present", descriptionText:"Home occupied (location provided)"],  
				2:[name:"presence" , value:"present", descriptionText:"Home occupied"]
				]
				
		].get(cmd.notificationType as Integer)?.get(cmd.event as Integer)

		if (notificationEvent.is( null )) return null
		
		if ((cmd.event == 0) && (cmd.eventParametersLength == 1)) { // This is for clearing events.
				return notificationEvent.get(cmd.eventParameter[0] as Integer) + ([deviceType:"ZWV", zwaveOriginalMessage:cmd.format()])
		}
		
		if (cmd.eventParametersLength > 1) { // This is unexpected! None of the current notifications use this.
			log.error "In function getZWaveNotificationEvent(), received command with eventParametersLength of unexpected size."
			return null
		} 
		return notificationEvent + [deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, ep = null )
{
	List<com.hubitat.app.DeviceWrapper> targetDevices = getTargetDeviceListByEndPoint(ep)

	Map thisEvent = getFormattedZWaveNotificationEvent(cmd)

	if ( ! thisEvent ) { 
		if ( logEnable ) log.debug "Device ${device.displayName}: Received an unhandled report ${cmd} for endpoint ${ep}." 
	} else { 
		Boolean targetNotified = false
		targetDevices.each {
			if (it.hasAttribute(thisEvent.name)) { 
				it.sendEvent(thisEvent) 
				targetNotified = true
			}
		}
		if (! targetNotified) {
				log.warn "Device ${device.displayName}: Device does not support attribute ${thisEvent.name}, endpoint ${ep?:0}, Zwave report: ${cmd}."
			}
	}
}