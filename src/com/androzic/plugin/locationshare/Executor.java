package com.androzic.plugin.locationshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;

public class Executor extends BroadcastReceiver
{
	@Override
	public void onReceive(Context context, Intent intent)
	{
		String action = intent.getAction();
		if (action.equals("com.androzic.plugins.action.INITIALIZE"))
		{
			PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
		}
		else if (action.equals("com.androzic.plugins.action.FINALIZE"))
		{
			context.stopService(new Intent(context, SharingService.class));
		}
	}
}
