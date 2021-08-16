library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handles Zwave Notifications",
        name: "notificationTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.hubTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/notificationTools.groovy"
)
/*
Relevant Z-Wave standards
SDS13713 = Silicon Labs Zwave Standard
*/
//////////////////////////////////////////////////////////////////////
//////        Handle   Notifications     ///////
//////////////////////////////////////////////////////////////////////


void	notificationTools_refresh(ep = null ) {
	Map specifiedNotifications = getEndpointNotificationsSupported(ep)
	if (specifiedNotifications)
	{ 
		specifiedNotifications.each{type, events ->
				performRefreshByType(type, events, ep)
				}
	}	else  {
		basicZwaveSend(zwave.notificationV8.notificationSupportedGet(), ep)
	}
}

void performRefreshByType(type, events, ep)
{
	// type is a single integer item corrensponding to Column B of zwave standard SDS13713
	//	Events is a list of integers identifying the sub-events for the type. This correspondes to column G of zwave standard SDS13713.
	events.each{ it ->
		basicZwaveSend(zwave.notificationV8.notificationGet(v1AlarmType:0, event: (it as Integer), notificationType: type), ep)
	}
}

List<Integer> getNotificationTypesList(def cmd) {
	List<Integer> notificationTypes = []
	
	if (cmd.smoke)				notificationTypes += 1 // Smoke
	if (cmd.co)					notificationTypes += 2 // CO
	if (cmd.co2)				notificationTypes += 3 // CO2
	if (cmd.heat)				notificationTypes += 4 // Heat
	if (cmd.water)				notificationTypes += 5 // Water
	if (cmd.accessControl) 		notificationTypes += 6 // Access Control
	if (cmd.burglar)			notificationTypes += 7 // Burglar
	if (cmd.powerManagement)	notificationTypes += 8 // Power Management
	if (cmd.system)				notificationTypes += 9 // System
	if (cmd.emergency)			notificationTypes += 10 // Emergency Alarm
	if (cmd.clock)				notificationTypes += 11 // Clock
	if (cmd.appliance)			notificationTypes += 12 // Appliance
	if (cmd.homeHealth)			notificationTypes += 13 // Home Health
	if (cmd.siren)				notificationTypes += 14 // Siren
	if (cmd.waterValve)			notificationTypes += 15 // Water Valve
	if (cmd.weatherAlarm)		notificationTypes += 16 // Weather Alarm
	if (cmd.irrigation)			notificationTypes += 17 // Irrigation
	if (cmd.gasAlarm)			notificationTypes += 18 // Gas Alarm
	if (cmd.pestControl)		notificationTypes += 19 // Pest Control
	if (cmd.lightSensor)		notificationTypes += 20 // Light Sensor
	if (cmd.waterQuality)		notificationTypes += 21 // Water Quality
	if (cmd.homeMonitoring)		notificationTypes += 22 // Home Monitoring
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationSupportedReport report, ep = null )
{ 
	getNotificationTypesList(report).each{it -> 
			basicZwaveSend(zwave.notificationV8.eventSupportedGet(notificationType:(it as Integer)), ep)}
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.EventSupportedReport cmd, ep = null )
{
	// Build a map of the notifications supported by a device endpoint and store it in the endpoint data
	List supportedEventsByType = cmd.supportedEvents.findAll{k, v -> ((v as Boolean) == true) }.collect{k, v -> (k as Integer) }
	getEndpointNotificationsSupported(ep).put( (cmd.notificationType as Integer), supportedEventsByType)
}

Map getFormattedZWaveNotificationEvent(def cmd)
{
	Date currentDate = new Date()
	Map notificationEvent =
		[ 	0x01:[ // Smoke
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
			0x02:[ // CO
				0:[
						1:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],
						2:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],	
						4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
						5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
						7:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance required cleared, periodic inspection."],				
					], 
				1:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected (location provided)."], 
				2:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."],
				4:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."],				
				5:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."],				
				7:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."],
				],
			0x03:[ // CO2
				0:[
						1:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],
						2:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],	
						4:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
						5:[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."],				
						7:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)."],				
					], 
				1:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected (location provided)."], 
				2:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."],
				4:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."],				
				5:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."],				
				7:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."],
				],
			0x04:[ // Heat Alarm - requires custom attribute heatAlarm
				0:[
						1:[name:"heatAlarm" , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."],
						2:[name:"heatAlarm" , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."],
						5:[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."],
						6:[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."],
						8:[name:"consumableStatus " , value:"good", descriptionText:"Replacement required (End-of-Life)."],				
						10:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)"],				
						11:[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)"],
						12:[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."],
						13:[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."],	
				], 
				1:[name:"heatAlarm" , value:"overheat", descriptionText:"Overheat detected, Location Provided."],
				2:[name:"heatAlarm" , value:"overheat", descriptionText:"Overheat detected, Unknown Location."],
				3:[name:"heatAlarm " , value:"rapidRise", descriptionText:"Rapid Temperature Rise detected, Location Provided."],
				4:[name:"heatAlarm " , value:"rapidRise", descriptionText:"Rapid Temperature Rise detected, Unknown Location."],				
				5:[name:"heatAlarm " , value:"underheat", descriptionText:"Underheat detected, Location Provided."],
				6:[name:"heatAlarm " , value:"underheat", descriptionText:"Underheat detected, Unknown Location."],
				8:[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."],				
				10:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."],				
				11:[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."],
				12:[name:"heatAlarm " , value:"rapidFall", descriptionText:"Rapid Temperature Fall detected, Location Provided."],
				13:[name:"heatAlarm " , value:"rapidFall", descriptionText:"Rapid Temperature Fall detected, Unknown Location."],				
				],				
			0x05:[ // Water	
				0:[
						1:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
						2:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
						5:[name:"filterStatus " , value:"normal", descriptionText:"Water filter good."],				

				], 
				1:[name:"water" , value:"wet", descriptionText:"Water leak detected (location provided)."], 
				2:[name:"water" , value:"wet", descriptionText:"Water leak detected."],
				5:[name:"filterStatus " , value:"replace", descriptionText:"Replace water filter (End-of-Life)."],				

				],
			0x06:[ // Access Control (Locks)
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
			0x07:[ // Home Security
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
			0x09:[ // System
				5:[name:"heartbeat" , value:currentDate, descriptionText:"Last Heartbeat"]
				], 				
			0x12:[ //  Gas Alarm
				0:[	 // These events "clear" a sensor.	
						1:[name:"naturalGas" , value:"clear", descriptionText:"Combustible gas (cleared) (location provided)"], 	
						2:[name:"naturalGas" , value:"clear", descriptionText:"Combustible gas  (cleared) "], 
						5:[name:"naturalGas" , value:"clear", descriptionText:"Gas detector test completed."], 	
						6:[name:"consumableStatus" , value:"good", descriptionText:"Gas detector (good)"],
					], 
				1:[name:"naturalGas" , value:"detected", descriptionText:"Combustible gas detected (location provided)"], 	
				2:[name:"naturalGas" , value:"detected", descriptionText:"Combustible gas detected"], 
				5:[name:"naturalGas" , value:"tested", descriptionText:"Gas detector test"], 	
				6:[name:"consumableStatus" , value:"replace", descriptionText:"Gas detector, replacement required"],
				],				
			0x0E:[ // Siren
				0:[
						1:[name:"alarm" , value:"off", descriptionText:"Alarm Siren Off."]
					], 
				1:[name:"alarm" , value:"siren", descriptionText:"Alarm Siren On."]
				], 
			0x0F:[ // Water Valve
				0:[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Valve Operation."], 
				1:[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Master Valve Operation."] 
				], 
	
			0x16:[ // Home Monitoring
				0:[
						1:[name:"presence" , value:"not present", descriptionText:"Home not occupied"],
						2:[name:"presence" , value:"not present", descriptionText:"Home not occupied"]
					], 
				1:[name:"presence" , value:"present", descriptionText:"Home occupied (location provided)"],  
				2:[name:"presence" , value:"present", descriptionText:"Home occupied"]
				]
				
		].get(cmd.notificationType as Integer)?.get(cmd.event as Integer)

		if (notificationEvent.is( null )) 
		{
		return [name:"unhandledZwaveEvent", value:cmd.format(), deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]
		}
		
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
	if (userNotificationReportFilter) cmd = userNotificationReportFilter(cmd)
	
	Map thisEvent = getFormattedZWaveNotificationEvent(cmd)
	
	if (userNotificationEventFilter) thisEvent = userNotificationEventFilter(thisEvent)

	sendEventToEndpoints(event:thisEvent, ep:ep, alwaysSend:["heartbeat"])

}