/**
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.inputmethod.dictionarypack;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.animation.AnimationUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.android.inputmethod.latin.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Preference screen.
 */
public final class DictionarySettingsFragment extends PreferenceFragment
        implements UpdateHandler.UpdateEventListener {
    private static final String TAG = DictionarySettingsFragment.class.getSimpleName();

    static final private String DICT_LIST_ID = "list";
    static final public String DICT_SETTINGS_FRAGMENT_CLIENT_ID_ARGUMENT = "clientId";

    static final private int MENU_UPDATE_NOW = Menu.FIRST;

    private View mLoadingView;
    private String mClientId;
    private ConnectivityManager mConnectivityManager;
    private MenuItem mUpdateNowMenu;
    private boolean mChangedSettings;

    private final BroadcastReceiver mConnectivityChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                refreshNetworkState();
            }
        };

    /**
     * Empty constructor for fragment generation.
     */
    public DictionarySettingsFragment() {
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View v = inflater.inflate(R.layout.loading_page, container, true);
        mLoadingView = v.findViewById(R.id.loading_container);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        final Activity activity = getActivity();
        mClientId = activity.getIntent().getStringExtra(DICT_SETTINGS_FRAGMENT_CLIENT_ID_ARGUMENT);
        mConnectivityManager =
                (ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        addPreferencesFromResource(R.xml.dictionary_settings);
        refreshInterface();
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        mUpdateNowMenu = menu.add(Menu.NONE, MENU_UPDATE_NOW, 0, R.string.check_for_updates_now);
        mUpdateNowMenu.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        refreshNetworkState();
    }

    @Override
    public void onResume() {
        super.onResume();
        mChangedSettings = false;
        UpdateHandler.registerUpdateEventListener(this);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        getActivity().registerReceiver(mConnectivityChangedReceiver, filter);
        refreshNetworkState();
    }

    @Override
    public void onPause() {
        super.onPause();
        final Activity activity = getActivity();
        UpdateHandler.unregisterUpdateEventListener(this);
        activity.unregisterReceiver(mConnectivityChangedReceiver);
        if (mChangedSettings) {
            final Intent newDictBroadcast =
                    new Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION);
            activity.sendBroadcast(newDictBroadcast);
            mChangedSettings = false;
        }
    }

    public void downloadedMetadata(final boolean succeeded) {
        stopLoadingAnimation();
        if (!succeeded) return; // If the download failed nothing changed, so no need to refresh
        new Thread("refreshInterface") {
            @Override
            public void run() {
                refreshInterface();
            }
        }.start();
    }

    public void wordListDownloadFinished(final String wordListId, final boolean succeeded) {
        final WordListPreference pref = findWordListPreference(wordListId);
        if (null == pref) return;
        // TODO: Report to the user if !succeeded
        final Activity activity = getActivity();
        if (null == activity) return;
        activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // We have to re-read the db in case the description has changed, and to
                    // find out what state it ended up if the download wasn't successful
                    // TODO: don't redo everything, only re-read and set this word list status
                    refreshInterface();
                }
            });
    }

    private WordListPreference findWordListPreference(final String id) {
        final PreferenceGroup prefScreen = getPreferenceScreen();
        if (null == prefScreen) {
            Log.e(TAG, "Could not find the preference group");
            return null;
        }
        for (int i = prefScreen.getPreferenceCount() - 1; i >= 0; --i) {
            final Preference pref = prefScreen.getPreference(i);
            if (pref instanceof WordListPreference) {
                final WordListPreference wlPref = (WordListPreference)pref;
                if (id.equals(wlPref.mWordlistId)) {
                    return wlPref;
                }
            }
        }
        Log.e(TAG, "Could not find the preference for a word list id " + id);
        return null;
    }

    public void updateCycleCompleted() {}

    private void refreshNetworkState() {
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
        boolean isConnected = null == info ? false : info.isConnected();
        if (null != mUpdateNowMenu) mUpdateNowMenu.setEnabled(isConnected);
    }

    private void refreshInterface() {
        final Activity activity = getActivity();
        if (null == activity) return;
        final long lastUpdateDate =
                MetadataDbHelper.getLastUpdateDateForClient(getActivity(), mClientId);
        final PreferenceGroup prefScreen = getPreferenceScreen();
        final Collection<? extends Preference> prefList =
                createInstalledDictSettingsCollection(mClientId);

        final String updateNowSummary = getString(R.string.last_update) + " "
                + DateUtils.formatDateTime(activity, lastUpdateDate,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);

        activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // TODO: display this somewhere
                    // if (0 != lastUpdate) mUpdateNowPreference.setSummary(updateNowSummary);
                    refreshNetworkState();

                    removeAnyDictSettings(prefScreen);
                    for (Preference preference : prefList) {
                        prefScreen.addPreference(preference);
                    }
                }
            });
    }

    private Preference createErrorMessage(final Activity activity, final int messageResource) {
        final Preference message = new Preference(activity);
        message.setTitle(messageResource);
        message.setEnabled(false);
        return message;
    }

    private void removeAnyDictSettings(final PreferenceGroup prefGroup) {
        for (int i = prefGroup.getPreferenceCount() - 1; i >= 0; --i) {
            prefGroup.removePreference(prefGroup.getPreference(i));
        }
    }

    /**
     * Creates a WordListPreference list to be added to the screen.
     *
     * This method only creates the preferences but does not add them.
     * Thus, it can be called on another thread.
     *
     * @param clientId the id of the client for which we want to display the dictionary list
     * @return A collection of preferences ready to add to the interface.
     */
    private Collection<? extends Preference> createInstalledDictSettingsCollection(
            final String clientId) {
        // This will directly contact the DictionaryProvider and request the list exactly like
        // any regular client would do.
        // Considering the respective value of the respective constants used here for each path,
        // segment, the url generated by this is of the form (assuming "clientId" as a clientId)
        // content://com.android.inputmethod.latin.dictionarypack/clientId/list?procotol=2
        final Uri contentUri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(getString(R.string.authority))
                .appendPath(clientId)
                .appendPath(DICT_LIST_ID)
                // Need to use version 2 to get this client's list
                .appendQueryParameter(DictionaryProvider.QUERY_PARAMETER_PROTOCOL_VERSION, "2")
                .build();
        final Activity activity = getActivity();
        final Cursor cursor = null == activity ? null
                : activity.getContentResolver().query(contentUri, null, null, null, null);

        if (null == cursor) {
            final ArrayList<Preference> result = new ArrayList<Preference>();
            result.add(createErrorMessage(activity, R.string.cannot_connect_to_dict_service));
            return result;
        } else if (!cursor.moveToFirst()) {
            final ArrayList<Preference> result = new ArrayList<Preference>();
            result.add(createErrorMessage(activity, R.string.no_dictionaries_available));
            return result;
        } else {
            final String systemLocaleString = Locale.getDefault().toString();
            final TreeMap<String, WordListPreference> prefList =
                    new TreeMap<String, WordListPreference>();
            final int idIndex = cursor.getColumnIndex(MetadataDbHelper.WORDLISTID_COLUMN);
            final int versionIndex = cursor.getColumnIndex(MetadataDbHelper.VERSION_COLUMN);
            final int localeIndex = cursor.getColumnIndex(MetadataDbHelper.LOCALE_COLUMN);
            final int descriptionIndex = cursor.getColumnIndex(MetadataDbHelper.DESCRIPTION_COLUMN);
            final int statusIndex = cursor.getColumnIndex(MetadataDbHelper.STATUS_COLUMN);
            do {
                final String wordlistId = cursor.getString(idIndex);
                final int version = cursor.getInt(versionIndex);
                final String localeString = cursor.getString(localeIndex);
                final Locale locale = new Locale(localeString);
                final String description = cursor.getString(descriptionIndex);
                final int status = cursor.getInt(statusIndex);
                final int matchLevel = LocaleUtils.getMatchLevel(systemLocaleString, localeString);
                final String matchLevelString = LocaleUtils.getMatchLevelSortedString(matchLevel);
                // The key is sorted in lexicographic order, according to the match level, then
                // the description.
                final String key = matchLevelString + "." + description + "." + wordlistId;
                final WordListPreference existingPref = prefList.get(key);
                if (null == existingPref || hasPriority(status, existingPref.mStatus)) {
                    final WordListPreference pref = new WordListPreference(activity, mClientId,
                            wordlistId, version, locale, description, status);
                    prefList.put(key, pref);
                }
            } while (cursor.moveToNext());
            return prefList.values();
        }
    }

    /**
     * Finds out if a given status has priority over another for display order.
     *
     * @param newStatus
     * @param oldStatus
     * @return whether newStatus has priority over oldStatus.
     */
    private static boolean hasPriority(final int newStatus, final int oldStatus) {
        // Both of these should be one of MetadataDbHelper.STATUS_*
        return newStatus > oldStatus;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case MENU_UPDATE_NOW:
            if (View.GONE == mLoadingView.getVisibility()) {
                startRefresh();
            } else {
                cancelRefresh();
            }
            return true;
        }
        return false;
    }

    private void startRefresh() {
        startLoadingAnimation();
        mChangedSettings = true;
        UpdateHandler.registerUpdateEventListener(this);
        final Activity activity = getActivity();
        new Thread("updateByHand") {
            @Override
            public void run() {
                UpdateHandler.update(activity, true);
            }
        }.start();
    }

    private void cancelRefresh() {
        UpdateHandler.unregisterUpdateEventListener(this);
        final Context context = getActivity();
        UpdateHandler.cancelUpdate(context,
                MetadataDbHelper.getMetadataUriAsString(context, mClientId));
        stopLoadingAnimation();
    }

    private void startLoadingAnimation() {
        mLoadingView.setVisibility(View.VISIBLE);
        getView().setVisibility(View.GONE);
        mUpdateNowMenu.setTitle(R.string.cancel);
    }

    private void stopLoadingAnimation() {
        final View preferenceView = getView();
        final Activity activity = getActivity();
        if (null == activity) return;
        activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mLoadingView.setVisibility(View.GONE);
                    preferenceView.setVisibility(View.VISIBLE);
                    mLoadingView.startAnimation(AnimationUtils.loadAnimation(
                            getActivity(), android.R.anim.fade_out));
                    preferenceView.startAnimation(AnimationUtils.loadAnimation(
                            getActivity(), android.R.anim.fade_in));
                    mUpdateNowMenu.setTitle(R.string.check_for_updates_now);
                }
            });
    }
}