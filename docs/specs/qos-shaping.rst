
.. contents:: Table of Contents
      :depth: 3

=====================================
Traffic shaping with Ovsdb QoS queues
=====================================
QoS patches: https://git.opendaylight.org/gerrit/#/q/topic:qos-shaping

The current Boron implementation provides support for ingress rate limiting configuration of OVS.
The Carbon release will add egress traffic shaping to QoS feature set.
(Note, the direction of traffic flow (ingress, egress) is from the perspective of the OpenSwitch)

Problem description
===================
OVS supports traffic shaping for traffic that egresses from a switch. To utilize this functionality,
Genius implementation should be able to create 'set queue' output action upon connection of new
OpenFlow node.

Use Cases
---------
Use case 1: Allow Unimgr to shape egress traffic from UNI

Proposed change
===============
Unimgr or Neutron VPN creates ietf vlan interface for each port connected to particular service.
The Ovsdb provides a possibility to create QoS and mapped Queue with egress rate limits for
lower level port. Such queue should be created on parent physical interface of vlan or trunk member
port if service has definition of limits.
The ovsdb southbound provides interface for creation of ovs QoS and Queues.
This functionality may be utilized by netvirt qos service.
Below is the dump from ovsdb with queues created for one of the ports.

.. code::

   Port table
      _uuid : a6cf4ca9-b15c-4090-aefe-23af2d5ce4f2
      name                : "ens5"
      qos                 : 9779ce41-4347-4383-b308-75f46d6a258c
   QoS table
      _uuid               : 9779ce41-4347-4383-b308-75f46d6a258c
      other_config        : {max-rate="50000"}
      queues              : {1=3cc34bb7-7df8-4538-9fd7-4a6c6c467c69}
      type                : linux-htb
   Queue table
      _uuid               : 3cc34bb7-7df8-4538-9fd7-4a6c6c467c69
      dscp                : []
      other_config        : {max-rate="50000", min-rate="5000"}

The queues creation is out of scope of this document.
The definition of vlan or trunk member port  will be augmented with relevant queue reference 
and number if queue was created successful.
That will allow to create openflow ‘set_queue’ output action during service binding.

Pipeline changes
----------------
New 'set_queue' action will be supported in Egress Dispatcher table

=======================   ==========  ==========================================
Table                     Match       Action
=======================   ==========  ==========================================
Egress Dispatcher [220]   no changes  Set queue id (optional) and output to port
=======================   ==========  ==========================================


Yang changes
------------
A new augment "ovs-qos" is added to if:interface in odl-interface.yang

.. code::

   /* vlan port to qos queue */
    augment "/if:interfaces/if:interface" {
        ext:augment-identifier "ovs-qos";
        when "if:type = 'ianaift:l2vlan'";

        leaf ovs-qos-ref {
            type instance-identifier;
            description
              "represents whether service port has associated qos. A reference to a ovsdb QoS entry";
        }
        leaf service-queue-number {
            type uint32;
            description
              "specific queue number within the list of queues in the qos entry";
        }
    }

Configuration impact
---------------------
None

Clustering considerations
-------------------------
None

Other Infra considerations
--------------------------
None

Security considerations
-----------------------
None

Scale and Performance Impact
----------------------------
Additional OpenFlow action will be performed on part of the packages.
Egress packages will be processed via linux-htp if service configured accordanly.

Targeted Release
-----------------
Carbon

Alternatives
------------
The unified REST API for ovsdb port adjustment could be created if future release.
The QoS engress queues and ingress rate limiting should be a part of this API.
Usage
=====
User will configure unimgr service with egress rate limits.
That will follow to process described above.

Features to Install
-------------------
- odl-genius (unimgr using genius feature for flows creation)

REST API
--------
None

CLI
---
None

Implementation
==============

Assignee(s)
-----------
Primary assignee:
  konsta.pozdeev@hpe.com

Work Items
----------

Dependencies
============
Minimum OVS version 1.8.0 is required.

Testing
=======
Unimgr test cases with configured egress rate limits will cover this functionality.

Unit Tests
----------

Integration Tests
-----------------

CSIT
----

References
==========
[1] `OpenDaylight Documentation Guide <http://docs.opendaylight.org/en/latest/documentation.html>`

[2] https://specs.openstack.org/openstack/nova-specs/specs/kilo/template.html
