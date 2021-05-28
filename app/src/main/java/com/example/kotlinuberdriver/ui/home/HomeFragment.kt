package com.example.kotlinuberdriver.ui.home

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.kotlinuberdriver.Common
import com.example.kotlinuberdriver.DriverHomeActivity
import com.example.kotlinuberdriver.Model.EventBus.DriverRequestReceived
import com.example.kotlinuberdriver.R
import com.example.kotlinuberdriver.Remote.GoogleApi
import com.example.kotlinuberdriver.Remote.RetrofitClient
import com.example.kotlinuberdriver.Utils.UserUtils
import com.example.kotlinuberdriver.databinding.FragmentHomeBinding
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import java.io.IOException
import java.lang.StringBuilder
import java.util.*
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment(), OnMapReadyCallback {

    private lateinit var homeViewModel: HomeViewModel
    private lateinit var mMap: GoogleMap
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var mapFragment: SupportMapFragment

    //location
    private var locationRequest: LocationRequest?= null
    private var locationCallback: LocationCallback?= null
    private var fusedLocationProviderClient: FusedLocationProviderClient?= null

    //FirebaseReference
    private lateinit var onlineRef: DatabaseReference
    private lateinit var driversLocationRef: DatabaseReference
    private lateinit var geofire: GeoFire
    private var currentUserRef: DatabaseReference?= null

    //Route
    private val compositeDisposable = CompositeDisposable()
    private lateinit var googleApi: GoogleApi
    private var blackPolyline: Polyline?= null
    private var greyPolyline: Polyline?= null
    private var polylineOptions: PolylineOptions?= null
    private var blackPolylineOptions: PolylineOptions?= null
    private var polylineList: ArrayList<LatLng>?= null

    //decline request
    private var driverRequestReceived: DriverRequestReceived?= null
    private var countDownEvent: Disposable?= null

    private val onlineValueEventListener = object:ValueEventListener{
        override fun onDataChange(snapshot: DataSnapshot) {
            if(snapshot.exists() && currentUserRef != null){
                currentUserRef!!.onDisconnect().removeValue()
            }
        }

        override fun onCancelled(error: DatabaseError) {
            Snackbar.make(mapFragment.requireView(), error.message, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        initView()
        initDriverLocation()

        mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        registerOnlineSystem()
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)){
            EventBus.getDefault().register(this)
        }
    }

    override fun onDestroy() {
        fusedLocationProviderClient!!.removeLocationUpdates(locationCallback)
        geofire.removeLocation(FirebaseAuth.getInstance().currentUser!!.uid)
        onlineRef.removeEventListener(onlineValueEventListener)
        compositeDisposable.clear()
        if (EventBus.getDefault().hasSubscriberForEvent(DriverHomeActivity::class.java)){
            EventBus.getDefault().removeStickyEvent(DriverHomeActivity::class.java)
        }
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    override fun onMapReady(googleMap: GoogleMap?) {
        mMap = googleMap!!

        Dexter.withContext(requireContext())
            .withPermission(Manifest.permission.ACCESS_FINE_LOCATION,)
            .withListener(object:PermissionListener{
                override fun onPermissionGranted(p0: PermissionGrantedResponse?) {

                    if (ActivityCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Snackbar.make(binding.flRoot, getString(R.string.permission_require),
                            Snackbar.LENGTH_LONG).show()
                        return
                    }
                    mMap.isMyLocationEnabled = true
                    mMap.uiSettings.isMyLocationButtonEnabled = true
                    mMap.setOnMyLocationClickListener {
                        fusedLocationProviderClient!!.lastLocation
                            .addOnFailureListener { e ->
                                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                            }
                            .addOnSuccessListener { location ->
                                val userLatLng = LatLng(location.latitude, location.longitude)
                                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 18f))
                            }
                    }
                    val view = mapFragment.requireView()
                        .findViewById<View>("1".toInt())
                        .parent as View
                    val locationBtn = view.findViewById<View>("2".toInt())
                    val params = locationBtn.layoutParams as RelativeLayout.LayoutParams
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP, 0)
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
                    params.bottomMargin = 50

                    //location
                    buildLocationRequest()
                    buildLocationCallback()
                    updateLocation()
                }

                override fun onPermissionDenied(p0: PermissionDeniedResponse?) {
                    Toast.makeText(context, "Permission " + p0!!.permissionName+ " was denied",
                    Toast.LENGTH_SHORT).show()
                }

                override fun onPermissionRationaleShouldBeShown(
                    p0: PermissionRequest?,
                    p1: PermissionToken?
                ) {

                }
            }).check()

        try {
            val success = googleMap.setMapStyle(MapStyleOptions.
            loadRawResourceStyle(context, R.raw.uber_maps_style))
            if (!success) {
                Log.e("ERROR GET MAP STYLE", "parsing error")
            }
        } catch (e: Resources.NotFoundException) {
            Log.e("ERROR", e.message!!)
        }
        Snackbar.make(mapFragment.requireView(), "You're online", Snackbar.LENGTH_SHORT).show()
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    fun onDriverRequestReceived(event: DriverRequestReceived) {
        driverRequestReceived = event

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Snackbar.make(requireView(), getString(R.string.permission_require),
                Snackbar.LENGTH_LONG).show()
            return
        }
        fusedLocationProviderClient!!.lastLocation
            .addOnFailureListener { e->
                Snackbar.make(requireView(), e.message!!, Snackbar.LENGTH_LONG).show()
            }
            .addOnSuccessListener { location ->
                val originString = StringBuilder()
                    .append(location.latitude)
                    .append(",")
                    .append(location.longitude)
                    .toString()

                compositeDisposable.add(googleApi.getDirection(
                    "driving",
                    "less_driving",
                    originString,
                    event.pickupLocation,
                    getString(R.string.google_api_key))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe { it ->
                        //Log.d("API_RETURN", it)
                        try {
                            val jsonObject = JSONObject(it)
                            val jsonArray = jsonObject.getJSONArray("routes")
                            for (i in 0 until jsonArray.length()) {
                                val route = jsonArray.getJSONObject(i)
                                val poly = route.getJSONObject("overview_polyline")
                                val polyline = poly.getString("points")
                                polylineList = Common.decodePoly(polyline)
                            }
                            polylineOptions = PolylineOptions()
                            polylineOptions!!.color(Color.GRAY)
                            polylineOptions!!.width(12f)
                            polylineOptions!!.startCap(SquareCap())
                            polylineOptions!!.jointType(JointType.ROUND)
                            polylineOptions!!.addAll(polylineList!!)
                            greyPolyline = mMap.addPolyline(polylineOptions!!)

                            blackPolylineOptions = PolylineOptions()
                            blackPolylineOptions!!.color(Color.BLACK)
                            blackPolylineOptions!!.width(5f)
                            blackPolylineOptions!!.startCap(SquareCap())
                            blackPolylineOptions!!.jointType(JointType.ROUND)
                            blackPolylineOptions!!.addAll(polylineList!!)
                            blackPolyline = mMap.addPolyline(blackPolylineOptions!!)

                            //Animator
                            val valueAnimator = ValueAnimator.ofInt(0,100)
                            valueAnimator.duration = 1100
                            valueAnimator.repeatCount = ValueAnimator.INFINITE
                            valueAnimator.interpolator = LinearInterpolator()
                            valueAnimator.addUpdateListener {
                                val points = greyPolyline!!.points
                                val percentValue = it.animatedValue.toString().toInt()
                                val size = points.size
                                val newPoints = (size * (percentValue/100f)).toInt()
                                val p = points.subList(0, newPoints)
                                blackPolyline!!.points = p
                            }
                            valueAnimator.start()

                            val origin = LatLng(location.latitude, location.longitude)
                            val destination = LatLng(
                                event.pickupLocation.split(",")[0].toDouble(),
                                event.pickupLocation.split(",")[1].toDouble()
                            )

                            val latLngBound = LatLngBounds.Builder()
                                .include(origin)
                                .include(destination)
                                .build()
                            // add car icon for origin
                            val objects = jsonArray.getJSONObject(0)
                            val legs = objects.getJSONArray("legs")
                            val legsObject = legs.getJSONObject(0)
                            val time = legsObject.getJSONObject("duration")
                            val duration = time.getString("text")
                            val distanceEstimate = legsObject.getJSONObject("distance")
                            val distance = distanceEstimate.getString("text")

                            mMap.addMarker(MarkerOptions()
                                .position(destination)
                                .icon(BitmapDescriptorFactory.defaultMarker())
                                .title("Pickup Location"))

                            mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBound, 160))
                            mMap.moveCamera(CameraUpdateFactory.zoomTo(mMap.cameraPosition.zoom-1))

                            //display layout
                            binding.chipDecline.visibility = View.VISIBLE
                            binding.cvAccept.visibility = View.VISIBLE
                            binding.tvEstimateTime.text = duration
                            binding.tvEstimateDistance.text = distance

                            //countdown
                            Observable.interval(100, TimeUnit.MILLISECONDS)
                                .observeOn(AndroidSchedulers.mainThread())
                                .doOnNext {
                                    binding.circularProgresBar.progress += 1
                                    binding.circularProgresBar.setIndicatorColor(requireContext()
                                        .getColor(android.R.color.darker_gray))
                                    binding.tvCountdownAccept.text =
                                        binding.circularProgresBar.progress.toString()
                                }
                                .takeUntil{ aLong ->
                                    aLong == "100".toLong()
                                }
                                .doOnComplete{
                                    Toast.makeText(context, "Fake accept action",
                                        Toast.LENGTH_LONG).show()
                                }
                                .subscribe()

                        } catch (e: IOException) {
                            Toast.makeText(context, e.message!!, Toast.LENGTH_SHORT).show()
                        }
                    })
            }
    }

    private fun initView() {
        binding.chipDecline.setOnClickListener {
            if(driverRequestReceived != null) {
                if (countDownEvent != null) {
                    countDownEvent!!.dispose()
                }
                binding.chipDecline.visibility = View.GONE
                binding.cvAccept.visibility = View.GONE
                mMap.clear()
                binding.circularProgresBar.progress = 0
                UserUtils.sendDeclineRequest(binding.flRoot, activity, driverRequestReceived!!.key)
                driverRequestReceived = null
            }
        }
    }

    private fun initDriverLocation() {
        googleApi = RetrofitClient.instance!!.create(GoogleApi::class.java)
        onlineRef = FirebaseDatabase.getInstance().reference.child(".info/connected")

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Snackbar.make(binding.flRoot, getString(R.string.permission_require),
                Snackbar.LENGTH_LONG).show()
            return
        }

        buildLocationRequest()
        buildLocationCallback()
        updateLocation()
    }

    private fun buildLocationRequest() {
        if (locationRequest == null) {
            locationRequest = LocationRequest()
            locationRequest!!.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            locationRequest!!.fastestInterval = 15000
            locationRequest!!.interval = 10000
            locationRequest!!.smallestDisplacement = 50f
        }
    }

    private fun buildLocationCallback() {
        if (locationCallback == null) {
            locationCallback = object: LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    super.onLocationResult(locationResult)

                    val pos = LatLng(locationResult.lastLocation.latitude,
                        locationResult.lastLocation.longitude)
                    val geoCoder = Geocoder(requireContext(), Locale.getDefault())
                    val addressList: List<Address>?

                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 18f))
                    try {
                        addressList = geoCoder.getFromLocation(
                            locationResult.lastLocation.latitude,
                            locationResult.lastLocation.longitude, 1)
                        val cityName = addressList[0].locality

                        driversLocationRef = FirebaseDatabase.getInstance()
                            .getReference(Common.DRIVERS_LOCATION_REFERENCE)
                            .child(cityName)
                        currentUserRef = driversLocationRef
                            .child(FirebaseAuth.getInstance().currentUser!!.uid)
                        geofire = GeoFire(driversLocationRef)

                        //update location
                        geofire.setLocation(
                            FirebaseAuth.getInstance().currentUser!!.uid,
                            GeoLocation(locationResult.lastLocation.latitude,
                                locationResult.lastLocation.longitude)
                        ){ _: String?, error: DatabaseError? ->
                            if(error != null) {
                                Snackbar.make(mapFragment.requireView(), error.message,
                                    Snackbar.LENGTH_SHORT).show()
                            }
                        }
                        registerOnlineSystem()
                    }catch (e: IOException){
                        Snackbar.make(requireView(), e.message!!, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateLocation() {
        if (fusedLocationProviderClient == null) {
            fusedLocationProviderClient =
                LocationServices.getFusedLocationProviderClient(requireContext())
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Snackbar.make(
                    binding.flRoot, getString(R.string.permission_require),
                    Snackbar.LENGTH_LONG
                ).show()
                return
            }
            fusedLocationProviderClient!!.requestLocationUpdates(
                locationRequest, locationCallback, Looper.myLooper()
            )
        }
    }

    private fun registerOnlineSystem() {
        onlineRef.addValueEventListener(onlineValueEventListener)
    }
}