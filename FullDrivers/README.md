These are instructions for the "Any_Z_Wave_Device_Universal_Parent_Driver_v1_7_6_517.groovy" driver (a.k., the "Universal Driver").

# I. Installation
Before saying anything else, I'm putting this up front to prevent errors . . .

If you select this driver for any device, be sure to click "Initialize" after saving your driver choice or reboot.

Read below for other installation instructions.


# II. What is the Universal Driver

The Universal Driver is my attempt to write a single driver that works with "any" Z-Wave device.

To date, it will work with switches, dimmers, and fan controllers of any brand.  
* This includes devices with multiple endpoints, 
* And metering and other similar reports,
* and S2 security.

# III. Why hasn't this been done before and what problems does it solve?

## 3.1. Parameter Management
In general, Z-Wave requires device-specific / manufacturer-specific drivers because each device has a different set of manufacturer-specific configuration parameters.

The "Universal Driver" solves this problem by taking a more flexible approach to parameter and device management.
1. First, it implements a database where I define the characteristics of supported devices, including the parameters the device supports as well as the number of endponts, etc. (https://github.com/jvmahon/HubitatDriverTools/blob/main/zwaveTools.zwaveDeviceDatabase.groovy)

2. But that database doesn't include everything, so if device isn't in the database, the driver queries the (massive) Z-wave device database at https://www.opensmarthouse.org/zwavedatabase/ and downloads all the necessary information about the device and stores it into "state" for future use.

## 3.2. Device Control

A second problem is how to have a single driver, but be able to control different device types (switches, dimmers, fans, etc.) since they all use different controls.

The way this is solved is that the "driver" is organized as a "Parent" and the device controls are implemented as "child" devices using Hubitat's Generic Component Drivers. So, even a simple dimmer or switch will have both a "Parent" and "Child" devices.

For example, a basic Hubitat Dimmer is organized as a parent / child:
![image](https://user-images.githubusercontent.com/15061942/158058546-fcfa91b4-3928-4763-bc24-74aeb136f465.png)

The "parent" is where you manage things like device parameters. The "parent" also generates central scene responses (more on that later).  The "Child" device is how to interact with the device.

### 3.2(a). Setting Up the Child Devices
There is a feature to automatically create the child devices when you click "Initialize" on the "parent" page (or on a reboot). Unfortunately, at the moment, this does not always work reliably. But you can do it yourself.  The Parent device also includes a tool to create the child devices that you need . . .

(Don't try addnig child devices until after you first click on "Initialize" on the Parent or reboot)

![image](https://user-images.githubusercontent.com/15061942/158058681-46f53cff-9dce-46f7-8231-28c7f148b7b3.png)

The operation is pretty simple. Specify the name of the child, select its type (e.g., "Generic Component Dimmer") and the endpoint (enter 0 if the device only has a single function and no endponts - this is the most common entry). Then click on the  Add New Child device button and the child device is added.

### 3.2(b). Multiple Child Devices for the Same Endpoint

Note that you can add multiple "Child" devices for the same endpoint. For example, if your device was a Motion Detector that also had a Temperature Sensor feature, you could either add a single child "Generic Componenet Omni Sensor"  or else both a "Generic Componenet Motion Sensor" and a "Generic Component Temperature Sensor" 

As a "Bonus" you can add multiple child devices of the exact same type. So, for example, if your device is a basic switch, you could add 5 "Generic Component Switch" child devices for endpoint 0. Each of these child devices  will stay in "sync" and function as expected. You may be asking "why would one do that?"  The one use I have for this is when interacting with HomeBridge over MakerAPI  (see, e.g., https://www.npmjs.com/package/homebridge-hubitat) where I may want the same device to appear in multiple iOS "rooms".  By using multiple "child" components, I can include the device multiple times into HOmebridge.

For example, here is a Zen25 double outlet that has three endpoints, but where I've added duplicate child devices for endpoints #1 and #2.

![image](https://user-images.githubusercontent.com/15061942/158059078-b95d1a74-d312-43d9-9e06-4c98a7f6a00d.png)

# 4. Notes on Central Scene

* Note that central scene reports are always "generated" in the "Parent" so  if you want to trigger off the Central Scene Report, use the "Parent" device for detecting the button push, etc.
* But, if you select a "Component" with "Central Scene" in its name, the "Parent" will also push the report down to that component, so you can get the report there too. 

## 4.1. What about More than 2 Central Scene taps
Hubitat generally only supports Push, DoubleTap, Hold, and Release events.

This driver will, however, allow up to 5 taps to be reported (the maximum permitted in Z-Wave).

It does so through a custom attribute "multiTapButton".

MultiTapButton is a decimal number of the form X.Y where X is the button number and Y is the number of taps.

So, for example, if you tap button 1 three times, it will generate the value 1.3

You can then use this in Rule Machine by (i) selecting "Custom Attribute" as the Triggering event, then (ii) selecting the "parent" device of interest, then (iii) entering in the numeric value corresponding to the button number and count. For example, the 2.3 in the following example means "button #2, 3 taps"

![image](https://user-images.githubusercontent.com/15061942/158596776-c565070d-b147-466e-8e98-8264274fb0e1.png)

![image](https://user-images.githubusercontent.com/15061942/158059358-6ad21082-8b56-4a3b-940e-8ab465731a24.png)

## 4.2. Central Scene In Child devices

As mentioned above, Central Scene processing is generally handled in the Parent device. Thus, you should identify the "parent" as the button device. That being said, if you select a "Child" device that supports central scene (Pushed, DoubleTap, Held, Released), then the child device will also register the event. Note that the "Generic Component" devices provided by Hubitat do not recognize the multiTapButton event.










