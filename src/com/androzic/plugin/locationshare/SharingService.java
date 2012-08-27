package com.androzic.plugin.locationshare;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

import com.androzic.data.Situation;
import com.androzic.location.ILocationCallback;
import com.androzic.location.ILocationRemoteService;

public class SharingService extends Service implements OnSharedPreferenceChangeListener
{
	private static final String TAG = "LocationSharing";
	private static final int NOTIFICATION_ID = 24164;

	public static final String BROADCAST_SITUATION_CHANGED = "com.androzic.sharingSituationChanged";
	// TODO Should import it from LocationService
	private static final String BROADCAST_LOCATING_STATUS = "com.androzic.locatingStatusChanged";

	private ILocationRemoteService locationService = null;

	private boolean errorState = false;
	private boolean sharingEnabled = false;
	private boolean isSuspended = false;

	private Notification notification;
	private PendingIntent contentIntent;
	
	protected ThreadPoolExecutor executorThread = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(1));
	
	Location currentLocation = new Location("fake");
	String session;
	String user;
	private int updateInterval = 10000; // 10 seconds (default)
	private long lastShareTime = 0;
	private int timeoutInterval = 600000; // 10 minutes (default)
	double speedFactor = 1;

	Map<String, Situation> situations;
	List<Situation> situationList;

	@Override
	public void onCreate()
	{
		super.onCreate();

		// Prepare notification components
		notification = new Notification();
		contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, SituationList.class), 0);

		// Initialize data structures
		situations = new HashMap<String, Situation>();
		situationList = new ArrayList<Situation>();

		// Inintialize preferences
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_session));
		onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_user));
		onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_updateinterval));
		onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_tagcolor));
		onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_tagcolor));
		onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_tagwidth));
		onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_timeout));
		sharedPreferences.registerOnSharedPreferenceChangeListener(this);

		// Register location service status receiver
		registerReceiver(broadcastReceiver, new IntentFilter(BROADCAST_LOCATING_STATUS));

		// Connect to location service
		prepareNormalNotification();
		sharingEnabled = true;
		isSuspended = true;
		connect();

		Log.i(TAG, "Service started");
	}

	@Override
	public void onDestroy()
	{
		super.onDestroy();

		// Disconnect from location service
		unregisterReceiver(broadcastReceiver);
		disconnect();
		stopForeground(true);

		notification = null;
		contentIntent = null;

		Log.i(TAG, "Service stopped");
	}

	protected void updateSituation(final Location loc)
	{
		executorThread.getQueue().poll();
		executorThread.execute(new Runnable() {
			public void run()
			{
				Log.e(TAG, "updateSituation");
				URI URL;
				try
				{
					String query = "session=" + URLEncoder.encode(session) + ";user=" + URLEncoder.encode(user) + ";lat=" + loc.getLatitude() + ";lon=" + loc.getLongitude() + ";track=" + loc.getBearing() + ";speed=" + loc.getSpeed() + ";ftime=" + loc.getTime();
					URL = new URI("http", null, "androzic.com", 80, "/cgi-bin/loc.cgi", query, null);
					Log.w(TAG, "URL: " + URL.toString());

					HttpClient httpclient = new DefaultHttpClient();
					HttpResponse response = httpclient.execute(new HttpGet(URL));
					StatusLine statusLine = response.getStatusLine();
					if (statusLine.getStatusCode() == HttpStatus.SC_OK)
					{
						ByteArrayOutputStream out = new ByteArrayOutputStream();
						response.getEntity().writeTo(out);
						out.close();
						String responseString = out.toString();
						JSONObject sts = new JSONObject(responseString);
						JSONArray entries = sts.getJSONArray("users");
						
						for (int i = 0; i < entries.length(); i++)
						{
							JSONObject situation = entries.getJSONObject(i);
							String name = situation.getString("user");
							if (name.equals(user))
								continue;
							synchronized (situations)
							{
								Situation s = situations.get(name);
								if (s == null)
								{
									s = new Situation(name);
									situations.put(name, s);
									situationList.add(s);
								}
								s.latitude = situation.getDouble("lat");
								s.longitude = situation.getDouble("lon");
								s.speed = situation.getDouble("speed");
								s.track = situation.getDouble("track");
								s.time = situation.getLong("ftime");
								s.silent = s.time + timeoutInterval < loc.getTime();
							}
						}
						sendBroadcast(new Intent(BROADCAST_SITUATION_CHANGED));
						lastShareTime = loc.getTime();
					}
					else
					{
						response.getEntity().getContent().close();
						throw new IOException(statusLine.getReasonPhrase());
					}
				}
				catch (URISyntaxException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				catch (ClientProtocolException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				catch (IOException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				catch (JSONException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		});
	}

	private void connect()
	{
		bindService(new Intent("com.androzic.location"), locationConnection, BIND_AUTO_CREATE);
	}

	private void disconnect()
	{
		if (locationService != null)
		{
			try
			{
				locationService.unregisterCallback(locationCallback);
			}
			catch (RemoteException e)
			{
			}
			unbindService(locationConnection);
			locationService = null;
		}
	}

	private void doStart()
	{
		if (isSuspended)
		{
			if (sharingEnabled)
			{
				startForeground(NOTIFICATION_ID, notification);
			}
			isSuspended = false;
		}
	}

	private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent)
		{
			String action = intent.getAction();
			Log.e(TAG, "Broadcast: " + action);
			if (action.equals(BROADCAST_LOCATING_STATUS))
			{
				boolean isLocating = false;
				try
				{
					isLocating = locationService != null && locationService.isLocating();
				}
				catch (RemoteException e)
				{
					e.printStackTrace();
				}
				if (isLocating)
				{
					doStart();
				}
				else if (sharingEnabled)
				{
					stopForeground(true);
					isSuspended = true;
				}
			}
		}
	};

	private void prepareNormalNotification()
	{
		notification.when = 0;
		notification.icon = R.drawable.ic_stat_sharing;
		notification.setLatestEventInfo(getApplicationContext(), getText(R.string.pref_sharing_title), getText(R.string.notif_sharing), contentIntent);
		errorState = false;
	}

	private void showErrorNotification()
	{
		if (errorState)
			return;

		notification.when = System.currentTimeMillis();
		notification.defaults |= Notification.DEFAULT_SOUND;
		/*
		 * Red icon (white): saturation +100, lightness -40 Red icon (grey):
		 * saturation +100, lightness 0
		 */
//		notification.icon = R.drawable.ic_stat_sharing_error;
		notification.setLatestEventInfo(getApplicationContext(), getText(R.string.pref_sharing_title), getText(R.string.notif_error), contentIntent);
		NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		nm.notify(NOTIFICATION_ID, notification);

		errorState = true;
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
	{
		String oldsession = session;
		String olduser = user;
		
		if (getString(R.string.pref_sharing_session).equals(key))
		{
			session = sharedPreferences.getString(key, "");
		}
		else if (getString(R.string.pref_sharing_user).equals(key))
		{
			user = sharedPreferences.getString(key, "");
		}
		else if (getString(R.string.pref_sharing_updateinterval).equals(key))
		{
	        updateInterval = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_updateinterval)) * 1000;
		}
		else if (getString(R.string.pref_sharing_tagcolor).equals(key))
		{
//	        linePaint.setColor(settings.getInt(key, context.getResources().getColor(R.color.usertag)));
		}
		else if (getString(R.string.pref_sharing_tagcolor).equals(key))
		{
//	        textPaint.setColor(settings.getInt(key, context.getResources().getColor(R.color.usertag)));
		}
		else if (getString(R.string.pref_sharing_tagwidth).equals(key))
		{
//	        pointWidth = settings.getInt(key, context.getResources().getInteger(R.integer.def_sharing_tagwidth));
		}
		else if (getString(R.string.pref_sharing_timeout).equals(key))
		{
	        timeoutInterval = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_timeout)) * 60000;
		}

		if (! session.equals(oldsession) || ! user.equals(olduser))
        {
			synchronized (situations)
			{
				situations.clear();
				situationList.clear();
			}
        }
        //FIXME should halt if any string is empty

        //TODO Get this from parent Application
		//int speedIdx = Integer.parseInt(settings.getString(context.getString(R.string.pref_unitspeed), "0"));
		//speedFactor = Double.parseDouble(context.getResources().getStringArray(R.array.speed_factors)[speedIdx]);
	}

	private final IBinder binder = new LocalBinder();

	public class LocalBinder extends Binder
	{
		public SharingService getService()
		{
			return SharingService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent)
	{
		return binder;
	}

	private ServiceConnection locationConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service)
		{
			locationService = ILocationRemoteService.Stub.asInterface(service);
			try
			{
				locationService.registerCallback(locationCallback);
				if (locationService.isLocating())
					doStart();
				Log.d(TAG, "Location service connected");
			}
			catch (RemoteException e)
			{
				e.printStackTrace();
			}
		}

		public void onServiceDisconnected(ComponentName className)
		{
			Log.d(TAG, "Location service disconnected");
			locationService = null;
		}
	};

	private ILocationCallback locationCallback = new ILocationCallback.Stub() {
		@Override
		public void onGpsStatusChanged(String provider, int status, int fsats, int tsats)
		{
			if (LocationManager.GPS_PROVIDER.equals(provider))
			{
				switch (status)
				{
				// case LocationService.GPS_OFF:
				// case LocationService.GPS_SEARCHING:
				// TODO Send lost location status
				}
			}
		}

		@Override
		public void onLocationChanged(Location loc, boolean continous, boolean geoid, float smoothspeed, float avgspeed)
		{
			Log.d(TAG, "Location arrived");
			currentLocation.set(loc);
			if (loc.getTime() - lastShareTime > updateInterval)
			{
				updateSituation(loc);
			}
		}

		@Override
		public void onProviderChanged(String provider)
		{
		}

		@Override
		public void onProviderDisabled(String provider)
		{
		}

		@Override
		public void onProviderEnabled(String provider)
		{
		}

		@Override
		public void onSensorChanged(float azimuth, float pitch, float roll)
		{
		}
	};

}
