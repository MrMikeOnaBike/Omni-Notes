/*
 * Copyright (C) 2015 Federico Iosue (federico.iosue@gmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.feio.android.omninotes.async;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import com.afollestad.materialdialogs.MaterialDialog;
import com.google.analytics.tracking.android.MapBuilder;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import it.feio.android.omninotes.OmniNotes;
import it.feio.android.omninotes.R;
import it.feio.android.omninotes.utils.ConnectionManager;
import it.feio.android.omninotes.utils.Constants;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLConnection;



public class UpdaterTask extends AsyncTask<String, Void, Void> {

	private final String BETA = " Beta ";
	private final WeakReference<Activity> mActivityReference;
	private final Activity mActivity;
	private final SharedPreferences prefs;
	private String packageName;
	private boolean promptUpdate = false;
	private long now;


	public UpdaterTask(Activity mActivity) {
		this.mActivityReference = new WeakReference<>(mActivity);
		this.mActivity = mActivity;
		this.prefs = mActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_MULTI_PROCESS);
	}


	@Override
	protected void onPreExecute() {
		now = System.currentTimeMillis();
		if (!ConnectionManager.internetAvailable(OmniNotes.getAppContext()) || now < prefs.getLong(Constants
				.PREF_LAST_UPDATE_CHECK, 0) + Constants.UPDATE_MIN_FREQUENCY) {
			cancel(true);
		}
		super.onPreExecute();
	}


	@Override
	protected Void doInBackground(String... params) {
		if (!isCancelled()) {
			String appData = getAppData();
			try {
				JSONObject json = new JSONObject(appData);
				promptUpdate = isVersionUpdated(json.getString("softwareVersion"));
				if (promptUpdate) {
					prefs.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, now).apply();
				}
			} catch (Exception e) {
				Log.w(Constants.TAG, "Error fetching app metadata", e);
			}
		}
		return null;
	}


	private void promptUpdate() {
		new MaterialDialog.Builder(mActivityReference.get())
				.title(R.string.app_name)
				.content(R.string.new_update_available)
				.positiveText(R.string.update)
				.negativeText(R.string.not_now)
				.negativeColorRes(R.color.colorPrimary)
				.callback(new MaterialDialog.ButtonCallback() {
					@Override
					public void onPositive(MaterialDialog materialDialog) {
						if (isGooglePlayAvailable()) {
							mActivityReference.get().startActivity(new Intent(
									Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
						} else {
							// MapBuilder.createEvent().build() returns a Map of event fields and values
							// that are set and sent with the hit.
							OmniNotes.getGaTracker().send(MapBuilder
									.createEvent("ui_action",     // Event category (required)
											"button_press",  // Event action (required)
											"Google Drive Update",   // Event label
											null)            // Event value
									.build());
							mActivityReference.get().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Constants
									.DRIVE_FOLDER_LAST_BUILD)));
						}
					}
				}).build().show();
	}


	@Override
	protected void onPostExecute(Void result) {
		if (isAlive(mActivityReference)) {
			if (promptUpdate) {
				promptUpdate();
			} else {
				showChangelog();
			}
		}
	}


	private void showChangelog() {
		try {
			String newVersion = mActivity.getPackageManager().getPackageInfo(mActivity.getPackageName(), 0)
					.versionName;
			String currentVersion = mActivity.getSharedPreferences(Constants.PREFS_NAME,
					Context.MODE_MULTI_PROCESS).getString(Constants.PREF_CURRENT_APP_VERSION, "");
			if (!newVersion.equals(currentVersion)) {
				new MaterialDialog.Builder(mActivity)
						.customView(R.layout.activity_changelog, false)
						.positiveText(R.string.ok)
						.build().show();
				mActivity.getSharedPreferences(Constants.PREFS_NAME,
						Context.MODE_MULTI_PROCESS).edit().putString(Constants.PREF_CURRENT_APP_VERSION,
						newVersion).commit();
			}
		} catch (NameNotFoundException e) {
			Log.e(Constants.TAG, "Error retrieving app version", e);
		}
	}


	/**
	 * Cheks if activity is still alive and not finishing
	 *
	 * @param weakActivityReference
	 * @return True or false
	 */
	private boolean isAlive(WeakReference<Activity> weakActivityReference) {
		return !(weakActivityReference.get() == null || weakActivityReference.get().isFinishing());
	}


	/**
	 * Fetches application data from internet
	 */
	public String getAppData() {
		StringBuilder sb = new StringBuilder();
		packageName = mActivity.getPackageName();
		try {
			URL url = new URL(Constants.PS_METADATA_FETCHER_URL + Constants.PLAY_STORE_URL + packageName);
			URLConnection conn = url.openConnection();
			InputStream is = conn.getInputStream();
			BufferedReader br = new BufferedReader(new InputStreamReader(is));

			String inputLine;

			while ((inputLine = br.readLine()) != null) {
				sb.append(inputLine);
			}
			is.close();

		} catch (IOException e) {
			Log.w(Constants.TAG, "Error fetching app metadata", e);
		}

		return sb.toString();
	}


	/**
	 * Checks parsing "android:versionName" if app has been updated
	 *
	 * @throws NameNotFoundException
	 */
	private boolean isVersionUpdated(String playStoreVersion)
			throws NameNotFoundException {

		boolean result = false;

		// Retrieval of installed app version
		PackageInfo pInfo = mActivity.getPackageManager().getPackageInfo(
				mActivity.getPackageName(), 0);
		String installedVersion = pInfo.versionName;

		// Parsing version string to obtain major.minor.point (excluding eventually beta)
		String[] playStoreVersionArray = playStoreVersion.split(BETA)[0].split("\\.");
		String[] installedVersionArray = installedVersion.split(BETA)[0].split("\\.");

		// Versions strings are converted into integer
		String playStoreVersionString = playStoreVersionArray[0];
		String installedVersionString = installedVersionArray[0];
		for (int i = 1; i < playStoreVersionArray.length; i++) {
			playStoreVersionString += String.format("%02d", Integer.parseInt(playStoreVersionArray[i]));
			installedVersionString += String.format("%02d", Integer.parseInt(installedVersionArray[i]));
		}

		// And then compared
		if (Integer.parseInt(playStoreVersionString) > Integer.parseInt(installedVersionString)) {
			result = true;
		}

		// And then compared again to check if we're out of Beta
		else if (Integer.parseInt(playStoreVersionString) == Integer.parseInt(installedVersionString)
				&& playStoreVersion.split("b").length == 1 && installedVersion.split("b").length == 2) {
			result = true;
		}

		return result;
	}


	/**
	 * Checks Google Play availability
	 */
	private boolean isGooglePlayAvailable() {
		boolean googlePlayStoreInstalled;
		int val = GooglePlayServicesUtil.isGooglePlayServicesAvailable(mActivity);
		googlePlayStoreInstalled = val == ConnectionResult.SUCCESS;
		return googlePlayStoreInstalled;
	}
}
