package com.example.rikharthu.directionconnection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.widget.ArrayAdapter
import kotlinx.android.synthetic.main.activity_main.*
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private val intentFilter = IntentFilter()
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var p2pReceiver: WifiP2pReceiver
    private var isWifiP2pEnabled = false
    private val peers = mutableListOf<WifiP2pDevice>()
    private lateinit var peerListAdapter: ArrayAdapter<String>
    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        Timber.d("Peers available: $peerList")
        val refreshedPeers = peerList.deviceList
        if (refreshedPeers != peers) {
            peers.clear()
            peers.addAll(refreshedPeers)
            peerListAdapter.clear()
            peerListAdapter.addAll(peers.map { "${it.deviceName}:${it.deviceAddress}" })
            peerListAdapter.notifyDataSetChanged()
        }
        if (peers.isEmpty()) {
            Timber.d("No devices found")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        with(intentFilter) {
            // Indicates a change in the Wi-Fi P2P status
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            // Indicates a change in the list of available peers
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            // Indicates the state of Wi-Fi P2P connectivity has changed
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            // Indicates this device's details have changed
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        discoverBtn.setOnClickListener {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.d("P2P discovery started successfully")
                }

                override fun onFailure(reason: Int) {
                    Timber.e("Could not start WiFi P2P discovery: $reason")
                }
            })
        }
        createGroupBtn.setOnClickListener { createGroup() }
        createServiceBtn.setOnClickListener { createService() }
        discoverServicesBtn.setOnClickListener {
            discoverServices()
        }

        peerListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        peerListView.adapter = peerListAdapter
        peerListView.setOnItemClickListener { parent, view, position, id ->
            connect(peers[position])
        }
    }

    private fun discoverServices() {
        // More info: https://developer.android.com/training/connect-devices-wirelessly/nsd-wifi-direct
        val txtListener = WifiP2pManager.DnsSdTxtRecordListener { fullDomainName, record, device ->
            Timber.d("DnsSdTxtRecord available: $record, ${device.deviceAddress}")
        }
        // Get service info
        val servListener = WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, resourceType ->
            val address = resourceType.deviceAddress
            Timber.d("DnsSdTxtRecord info available: $instanceName, $registrationType, $resourceType at $address")
        }
        manager.setDnsSdResponseListeners(channel, servListener, txtListener)
        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Added service request")
            }

            override fun onFailure(reason: Int) {
                Timber.e("Could not add service request: $reason")
            }
        })
        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Discovering services")
            }

            override fun onFailure(reason: Int) {
                Timber.e("Could not start service discovery: $reason")
            }
        })
    }

    private fun createService() {
        val listenPort = 9000
        // Create a string map containing information about your service
        val record = mutableMapOf<String, String>()
        record["listenport"] = listenPort.toString()
        record["buddyname"] = "John Doe " + Math.random() * 1000
        record["available"] = "visible"

        // Service information.  Pass it an instance name, service type
        // _protocol._transportlayer , and the map containing
        // information other devices will want once they connect to this one.
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("_test", "_presence._tcp", record)

        manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Service has been created")
            }

            override fun onFailure(reason: Int) {
                Timber.d("Failure creating service: $reason")
            }
        })
    }

    private fun createGroup() {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Group has been created")
            }

            override fun onFailure(reason: Int) {
                Timber.e("Could not create Wifi P2p group: $reason")
            }
        })
    }

    private fun connect(device: WifiP2pDevice) {
        Timber.d("Connecting to ${device.deviceName}:${device.deviceAddress}")
        val config = WifiP2pConfig()
        config.deviceAddress = device.deviceAddress
        config.wps.setup = WpsInfo.PBC
        config.groupOwnerIntent = 0

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {

            }

            override fun onFailure(reason: Int) {
                Timber.e("Could not connect to ${device.deviceName}:${device.deviceAddress}, reason: $reason")
            }
        })
    }

    override fun onResume() {
        super.onResume()
        p2pReceiver = WifiP2pReceiver()
        registerReceiver(p2pReceiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(p2pReceiver)
    }

    private fun onConnectionStateChanged(networkInfo: NetworkInfo) {
        Timber.d("Connection state changed")
        if (networkInfo.isConnected) {
            Timber.d("Connected to device")
            manager.requestConnectionInfo(channel) { info ->
                Timber.d("Connection info is available")
                // InetAddress from WifiP2pInfo struct.
                val groupOwnerAddress = info.groupOwnerAddress.hostAddress
                Timber.d("Group owner address: $groupOwnerAddress")

                // After the group negotiation, we can determine the group owner.
                if (info.groupFormed && info.isGroupOwner) {
                    // Do whatever tasks are specific to the group owner.
                    // One common case is creating a server thread and accepting
                    // incoming connections.
                    Timber.d("Is group owner")
                } else if (info.groupFormed) {
                    // The other device acts as the client. In this case,
                    // you'll want to create a client thread that connects to the group
                    // owner.
                    Timber.d("Other device is group owner")
                }
            }
        } else {
            Timber.d("Disconnected")
        }
    }

    private fun onPeersChanged() {
        Timber.d("P2P peers changed")
        // Request available peers from the wifi p2p manager
        manager.requestPeers(channel, peerListListener)
    }

    private fun onP2pStateChanged(state: Int) {
        Timber.d("P2P state changed to $state")
        // Determine if Wifi P2P mode is enabled or not
        isWifiP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
    }

    private inner class WifiP2pReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    onP2pStateChanged(intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1))
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    onPeersChanged()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    onConnectionStateChanged(networkInfo)
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Timber.d("WIFI_P2P_THIS_DEVICE_CHANGED_ACTION")
                }
                else -> {
                    Timber.d("Received unknown action: $action")
                }
            }
        }

    }
}
