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
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
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
import android.content.ContentProviderClient;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

import com.androzic.data.Situation;
import com.androzic.location.BaseLocationService;
import com.androzic.location.ILocationCallback;
import com.androzic.location.ILocationRemoteService;
import com.androzic.provider.DataContract;
import com.androzic.provider.PreferencesContract;
import com.androzic.util.StringFormatter;

public class SharingService extends Service implements OnSharedPreferenceChangeListener
{
	private static final String TAG = "LocationSharing";
	private static final int NOTIFICATION_ID = 24164;

	public static final String BROADCAST_SITUATION_CHANGED = "com.androzic.sharingSituationChanged";

	private ILocationRemoteService locationService = null;

	private boolean errorState = false;
	private boolean sharingEnabled = false;
	private boolean isSuspended = false;

	private Notification notification;
	private PendingIntent contentIntent;

	ThreadPoolExecutor executorThread = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(1));
	private Timer timer;

	private ContentProviderClient contentProvider;

	Location currentLocation = new Location("fake");
	String session;
	String user;
	private int updateInterval = 10000; // 10 seconds (default)
	int timeoutInterval = 600000; // 10 minutes (default)
	long timeCorrection = 0;
	double speedFactor = 1;
	String speedAbbr = "m/s";

	private Map<String, Situation> situations;
	List<Situation> situationList;

	// Drawing resources
	private Paint linePaint;
	private Paint textPaint;
	private Paint textFillPaint;
	private int pointWidth;

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

		// Connect to data provider
		contentProvider = getContentResolver().acquireContentProviderClient(DataContract.MAPOBJECTS_URI);

		// Initialize drawing resources
		Resources resources = getResources();
		linePaint = new Paint();
		linePaint.setAntiAlias(false);
		linePaint.setStrokeWidth(2);
		linePaint.setStyle(Paint.Style.STROKE);
		linePaint.setColor(resources.getColor(R.color.usertag));
		textPaint = new Paint();
		textPaint.setAntiAlias(true);
		textPaint.setStrokeWidth(2);
		textPaint.setStyle(Paint.Style.FILL);
		textPaint.setTextAlign(Align.LEFT);
		textPaint.setTextSize(20);
		textPaint.setTypeface(Typeface.SANS_SERIF);
		textPaint.setColor(resources.getColor(R.color.usertag));
		textFillPaint = new Paint();
		textFillPaint.setAntiAlias(false);
		textFillPaint.setStrokeWidth(1);
		textFillPaint.setStyle(Paint.Style.FILL_AND_STROKE);
		textFillPaint.setColor(resources.getColor(R.color.usertagwithalpha));

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

		readAndrozicPreferences();

		// Register location service status receiver
		registerReceiver(broadcastReceiver, new IntentFilter(BaseLocationService.BROADCAST_LOCATING_STATUS));

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
		stopTimer();

		// Clear data
		clearSituations();

		// Release data provider
		contentProvider.release();

		notification = null;
		contentIntent = null;

		Log.i(TAG, "Service stopped");
	}

	private void clearSituations()
	{
		String[] args = new String[situationList.size()];
		int i = 0;
		for (Situation situation : situationList)
		{
			args[i] = String.valueOf(situation._id);
			i++;
		}
		synchronized (situations)
		{
			situationList.clear();
			situations.clear();
		}
		// Remove situations from map
		try
		{
			contentProvider.delete(DataContract.MAPOBJECTS_URI, DataContract.MAPOBJECT_ID_SELECTION, args);
		}
		catch (RemoteException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		sendBroadcast(new Intent(BROADCAST_SITUATION_CHANGED));
	}

	protected void updateSituations()
	{
		notification.icon = R.drawable.ic_stat_sharing_out;
		final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		nm.notify(NOTIFICATION_ID, notification);

		executorThread.getQueue().poll();
		executorThread.execute(new Runnable() {
			public void run()
			{
				Log.e(TAG, "updateSituation");
				URI URL;
				boolean updated = false;
				try
				{
					String query = null;
					synchronized (currentLocation)
					{
						query = "session=" + URLEncoder.encode(session) + ";user=" + URLEncoder.encode(user) + ";lat=" + currentLocation.getLatitude() + ";lon=" + currentLocation.getLongitude()
								+ ";track=" + currentLocation.getBearing() + ";speed=" + currentLocation.getSpeed() + ";ftime=" + currentLocation.getTime();
					}
					URL = new URI("http", null, "androzic.com", 80, "/cgi-bin/loc.cgi", query, null);
					Log.w(TAG, "URL: " + URL.toString());

					HttpClient httpclient = new DefaultHttpClient();
					HttpResponse response = httpclient.execute(new HttpGet(URL));
					notification.icon = R.drawable.ic_stat_sharing_in;
					nm.notify(NOTIFICATION_ID, notification);
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
							}
						}
						updated = true;
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

				synchronized (situations)
				{
					long curTime = System.currentTimeMillis() - timeCorrection;
					for (Situation situation : situations.values())
					{
						situation.silent = situation.time + timeoutInterval < curTime;
					}
				}

				if (updated)
					sendBroadcast(new Intent(BROADCAST_SITUATION_CHANGED));

				try
				{
					sendMapObjects();
				}
				catch (RemoteException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				notification.icon = R.drawable.ic_stat_sharing;
				nm.notify(NOTIFICATION_ID, notification);
			}
		});
	}

	private void sendMapObjects() throws RemoteException
	{
		for (Situation situation : situationList)
		{
			byte[] bitmap = getSituationBitmap(situation);
			ContentValues values = new ContentValues();
			values.put(DataContract.MAPOBJECT_COLUMNS[DataContract.MAPOBJECT_LATITUDE_COLUMN], situation.latitude);
			values.put(DataContract.MAPOBJECT_COLUMNS[DataContract.MAPOBJECT_LONGITUDE_COLUMN], situation.longitude);
			values.put(DataContract.MAPOBJECT_COLUMNS[DataContract.MAPOBJECT_BITMAP_COLUMN], bitmap);
			if (situation._id == 0)
			{
				Uri uri = contentProvider.insert(DataContract.MAPOBJECTS_URI, values);
				situation._id = ContentUris.parseId(uri);
			}
			else
			{
				Uri uri = ContentUris.withAppendedId(DataContract.MAPOBJECTS_URI, situation._id);
				contentProvider.update(uri, values, null, null);
			}
		}
	}

	private byte[] getSituationBitmap(Situation situation)
	{
		Rect textRect = new Rect();
		String tag = String.valueOf(Math.round(situation.speed * speedFactor)) + "  " + String.valueOf(Math.round(situation.track));
		textPaint.getTextBounds(tag, 0, tag.length(), textRect);
		Rect rect1 = new Rect();
		textPaint.getTextBounds(situation.name, 0, situation.name.length(), rect1);
		textRect.union(rect1);
		int textHeight = textRect.height();
		textRect.inset(0, -(textHeight + 3) / 2);
		textRect.inset(-2, -2);
		int offset = pointWidth * 3;
		int width = textRect.width() + offset + 3;
		int height = textRect.height() + offset + 3;

		Bitmap b = Bitmap.createBitmap(width * 2, height * 2, Config.ARGB_8888);
		Canvas bc = new Canvas(b);

		linePaint.setAlpha(situation.silent ? 128 : 255);
		textPaint.setAlpha(situation.silent ? 128 : 255);

		bc.translate(width, height);
		int half = Math.round(pointWidth / 2);
		Rect tagRect = new Rect(-half, -half, +half, +half);
		bc.drawRect(tagRect, linePaint);
		bc.drawLine(0, 0, offset, -offset, linePaint);
		textRect.offsetTo(offset + 3, -offset - textHeight * 2 - 5);
		bc.drawRect(textRect, textFillPaint);
		bc.drawText(tag, offset + 5, -offset, textPaint);
		bc.drawText(situation.name, offset + 5, -offset - textHeight - 3, textPaint);
		bc.save();
		bc.rotate((float) situation.track, 0, 0);
		bc.drawLine(0, 0, 0, -pointWidth * 2, linePaint);
		bc.restore();

		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		b.compress(Bitmap.CompressFormat.PNG, 100, stream);
		return stream.toByteArray();
	}

	// This is not used in code, but included to demonstrate, how to remove
	// single map object from Androzic map.
	@SuppressWarnings("unused")
	private void removeMapObject(String name)
	{
		Situation situation = situations.get(name);
		situationList.remove(situation);
		synchronized (situations)
		{
			situations.remove(name);
		}
		Uri uri = ContentUris.withAppendedId(DataContract.MAPOBJECTS_URI, situation._id);
		try
		{
			contentProvider.delete(uri, null, null);
		}
		catch (RemoteException e)
		{
			e.printStackTrace();
		}
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

	private void startTimer()
	{
		if (timer != null)
			stopTimer();

		timer = new Timer();
		TimerTask updateTask = new UpdateSituationsTask();
		timer.scheduleAtFixedRate(updateTask, 0, updateInterval);
	}

	private void stopTimer()
	{
		timer.cancel();
		timer = null;
	}

	private void doStart()
	{
		if (isSuspended)
		{
			if (sharingEnabled)
			{
				startForeground(NOTIFICATION_ID, notification);
				startTimer();
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
			if (action.equals(BaseLocationService.BROADCAST_LOCATING_STATUS))
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
					stopTimer();
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
		// notification.icon = R.drawable.ic_stat_sharing_error;
		notification.setLatestEventInfo(getApplicationContext(), getText(R.string.pref_sharing_title), getText(R.string.notif_error), contentIntent);
		NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		nm.notify(NOTIFICATION_ID, notification);

		errorState = true;
	}

	// This is not used in code, but included to demonstrate, how to read
	// single preference from Androzic.
	@SuppressWarnings("unused")
	private void readAndrozicPreference()
	{
		ContentProviderClient client = getContentResolver().acquireContentProviderClient(PreferencesContract.PREFERENCES_URI);
		Uri uri = ContentUris.withAppendedId(PreferencesContract.PREFERENCES_URI, PreferencesContract.SPEED_FACTOR);
		try
		{
			Cursor cursor = client.query(uri, PreferencesContract.DATA_COLUMNS, null, null, null);
			cursor.moveToFirst();
			double speedFactor = cursor.getDouble(PreferencesContract.DATA_COLUMN);
		}
		catch (RemoteException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		client.release();
	}

	private void readAndrozicPreferences()
	{
		// Resolve content provider
		ContentProviderClient client = getContentResolver().acquireContentProviderClient(PreferencesContract.PREFERENCES_URI);

		// Setup preference items we want to read (order is important - it
		// should correlate with the read order later in code)
		int[] fields = new int[] { PreferencesContract.SPEED_FACTOR, PreferencesContract.SPEED_ABBREVIATION, PreferencesContract.DISTANCE_FACTOR, PreferencesContract.DISTANCE_ABBREVIATION,
				PreferencesContract.DISTANCE_SHORT_FACTOR, PreferencesContract.DISTANCE_SHORT_ABBREVIATION };
		// Convert them to strings
		String[] args = new String[fields.length];
		for (int i = 0; i < fields.length; i++)
		{
			args[i] = String.valueOf(fields[i]);
		}
		try
		{
			// Request data from preferences content provider
			Cursor cursor = client.query(PreferencesContract.PREFERENCES_URI, PreferencesContract.DATA_COLUMNS, PreferencesContract.DATA_SELECTION, args, null);
			cursor.moveToFirst();
			speedFactor = cursor.getDouble(PreferencesContract.DATA_COLUMN);
			cursor.moveToNext();
			speedAbbr = cursor.getString(PreferencesContract.DATA_COLUMN);
			cursor.moveToNext();
			StringFormatter.distanceFactor = cursor.getDouble(PreferencesContract.DATA_COLUMN);
			cursor.moveToNext();
			StringFormatter.distanceAbbr = cursor.getString(PreferencesContract.DATA_COLUMN);
			cursor.moveToNext();
			StringFormatter.distanceShortFactor = cursor.getDouble(PreferencesContract.DATA_COLUMN);
			cursor.moveToNext();
			StringFormatter.distanceShortAbbr = cursor.getString(PreferencesContract.DATA_COLUMN);
		}
		catch (RemoteException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		// Notify that the binding is not required anymore
		client.release();
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
			if (timer != null)
			{
				stopTimer();
				startTimer();
			}
		}
		else if (getString(R.string.pref_sharing_tagcolor).equals(key))
		{
			linePaint.setColor(sharedPreferences.getInt(key, getResources().getColor(R.color.usertag)));
		}
		else if (getString(R.string.pref_sharing_tagcolor).equals(key))
		{
			textPaint.setColor(sharedPreferences.getInt(key, getResources().getColor(R.color.usertag)));
		}
		else if (getString(R.string.pref_sharing_tagwidth).equals(key))
		{
			pointWidth = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_tagwidth));
		}
		else if (getString(R.string.pref_sharing_timeout).equals(key))
		{
			timeoutInterval = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_timeout)) * 60000;
		}

		if (!session.equals(oldsession) || !user.equals(olduser))
		{
			clearSituations();
		}
		if ((session != null && session.trim().equals("")) || (user != null && user.trim().equals("")))
			stopSelf();
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
			synchronized (currentLocation)
			{
				currentLocation.set(loc);
				timeCorrection = System.currentTimeMillis() - currentLocation.getTime();
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
	};

	class UpdateSituationsTask extends TimerTask
	{
		public void run()
		{
			updateSituations();
		}
	}
}
