/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tv.settings;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.support.v17.preference.LeanbackPreferenceFragment;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceGroup;
import android.text.TextUtils;
import android.util.Log;

import com.android.settingslib.accounts.AuthenticatorHelper;
import com.android.tv.settings.accessories.AccessoryUtils;
import com.android.tv.settings.accessories.BluetoothAccessoryFragment;
import com.android.tv.settings.accessories.BluetoothConnectionsManager;
import com.android.tv.settings.accounts.AccountSyncFragment;
import com.android.tv.settings.accounts.AddAccountWithTypeActivity;
import com.android.tv.settings.connectivity.ConnectivityListener;
import com.android.tv.settings.device.sound.SoundFragment;
import com.android.tv.settings.users.RestrictedProfileDialogFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainFragment extends LeanbackPreferenceFragment {
    private static final String TAG = "MainFragment";

    private static final String KEY_DEVELOPER = "developer";
    private static final String KEY_LOCATION = "location";
    private static final String KEY_SECURITY = "security";
    private static final String KEY_USAGE = "usageAndDiag";
    private static final String KEY_ADD_ACCOUNT = "add_account";
    private static final String KEY_ACCESSORIES = "accessories";
    private static final String KEY_PERSONAL = "personal";
    private static final String KEY_ADD_ACCESSORY = "add_accessory";
    private static final String KEY_NETWORK = "network";
    private static final String KEY_INPUTS = "inputs";
    private static final String KEY_SOUNDS = "sound_effects";
    private static final String KEY_GOOGLE_SETTINGS = "googleSettings";
    private static final String KEY_HOME_SETTINGS = "home";
    private static final String KEY_CAST_SETTINGS = "cast";
    private static final String KEY_SPEECH_SETTINGS = "speech";
    private static final String KEY_SEARCH_SETTINGS = "search";

    private static final String ACCOUNT_TYPE_GOOGLE = "com.google";

    private AuthenticatorHelper mAuthenticatorHelper;
    private BluetoothAdapter mBtAdapter;
    private ConnectivityListener mConnectivityListener;

    private boolean mInputSettingNeeded;

    private Preference mDeveloperPref;
    private List<Preference> mAccountPrefs = new ArrayList<>(4);
    private PreferenceGroup mPersonalGroup;
    private PreferenceGroup mAccessoriesGroup;
    private Preference mAddAccessory;
    private Preference mNetworkPref;
    private Preference mSoundsPref;

    private final BroadcastReceiver mBCMReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateAccessories();
        }
    };

    public static MainFragment newInstance() {
        return new MainFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mAuthenticatorHelper = new AuthenticatorHelper(getContext(),
                new UserHandle(UserHandle.myUserId()),
                new AuthenticatorHelper.OnAccountsUpdateListener() {
                    @Override
                    public void onAccountsUpdate(UserHandle userHandle) {
                        updateAccounts();
                    }
                });
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        mConnectivityListener = new ConnectivityListener(getContext(),
                new ConnectivityListener.Listener() {
                    @Override
                    public void onConnectivityChange(Intent intent) {
                        updateWifi();
                    }
                });

        final TvInputManager manager = (TvInputManager) getContext().getSystemService(
                Context.TV_INPUT_SERVICE);
        if (manager != null) {
            for (final TvInputInfo input : manager.getTvInputList()) {
                if (input.isPassthroughInput()) {
                    mInputSettingNeeded = true;
                }
            }
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        if (isRestricted()) {
            setPreferencesFromResource(R.xml.restricted_prefs, null);
        } else {
            setPreferencesFromResource(R.xml.main_prefs, null);
        }
        mDeveloperPref = findPreference(KEY_DEVELOPER);
        mAccessoriesGroup = (PreferenceGroup) findPreference(KEY_ACCESSORIES);
        mPersonalGroup = (PreferenceGroup) findPreference(KEY_PERSONAL);
        mAddAccessory = findPreference(KEY_ADD_ACCESSORY);
        mNetworkPref = findPreference(KEY_NETWORK);
        mSoundsPref = findPreference(KEY_SOUNDS);

        mAccountPrefs.add(findPreference(KEY_LOCATION));
        mAccountPrefs.add(findPreference(KEY_SECURITY));
        mAccountPrefs.add(findPreference(KEY_USAGE));
        mAccountPrefs.add(findPreference(KEY_ADD_ACCOUNT));

        final Preference inputPref = findPreference(KEY_INPUTS);
        if (inputPref != null) {
            inputPref.setVisible(mInputSettingNeeded);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mAuthenticatorHelper.listenToAccountUpdates();
        final IntentFilter filter =
                new IntentFilter(BluetoothConnectionsManager.ACTION_BLUETOOTH_UPDATE);
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(mBCMReceiver, filter);
    }

    @Override
    public void onResume() {
        super.onResume();

        updateAccounts();
        updateAccessories();
        updateDeveloperOptions();
        updateSounds();
        updateGoogleSettings();

        hideIfIntentUnhandled(findPreference(KEY_HOME_SETTINGS));
        hideIfIntentUnhandled(findPreference(KEY_CAST_SETTINGS));
        hideIfIntentUnhandled(findPreference(KEY_USAGE));
        hideIfIntentUnhandled(findPreference(KEY_SPEECH_SETTINGS));
        hideIfIntentUnhandled(findPreference(KEY_SEARCH_SETTINGS));
    }

    @Override
    public void onStop() {
        super.onStop();
        mAuthenticatorHelper.stopListeningToAccountUpdates();
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(mBCMReceiver);
    }

    private void hideIfIntentUnhandled(Preference preference) {
        if (preference == null) {
            return;
        }
        preference.setVisible(systemIntentIsHandled(preference.getIntent()) != null);
    }

    private boolean isRestricted() {
        return RestrictedProfileDialogFragment.isRestrictedProfileInEffect(getContext());
    }

    private void updateAccounts() {
        if (mPersonalGroup == null) {
            return;
        }

        mPersonalGroup.removeAll();
        for (final Preference reAdd : mAccountPrefs) {
            mPersonalGroup.addPreference(reAdd);
        }

        final AccountManager am = AccountManager.get(getContext());
        final AuthenticatorDescription[] authTypes = am.getAuthenticatorTypes();
        final ArrayList<String> allowableAccountTypes = new ArrayList<>(authTypes.length);
        final Context themedContext = getPreferenceManager().getContext();

        int googleAccountCount = 0;

        for (AuthenticatorDescription authDesc : authTypes) {
            final Context targetContext;
            try {
                targetContext = getContext().createPackageContext(authDesc.packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Authenticator description with bad package name", e);
                continue;
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception loading package resources", e);
                continue;
            }

            // Main title text comes from the authenticator description (e.g. "Google").
            String authTitle = null;
            try {
                authTitle = targetContext.getString(authDesc.labelId);
                if (TextUtils.isEmpty(authTitle)) {
                    authTitle = null;  // Handled later when we add the row.
                }
            } catch (Resources.NotFoundException e) {
                Log.e(TAG, "Authenticator description with bad label id", e);
            }

            // There exist some authenticators which aren't intended to be user-facing.
            // If the authenticator doesn't have a title or an icon, don't present it to
            // the user as an option.
            if (authTitle != null || authDesc.iconId != 0) {
                allowableAccountTypes.add(authDesc.type);
            }

            Account[] accounts = am.getAccountsByType(authDesc.type);
            if (accounts == null || accounts.length == 0) {
                continue;  // No point in continuing; there aren't any accounts to show.
            }

            // Icon URI to be displayed for each account is based on the type of authenticator.
            Drawable authImage = null;
            if (ACCOUNT_TYPE_GOOGLE.equals(authDesc.type)) {
                googleAccountCount = accounts.length;
                authImage = getActivity().getDrawable(R.drawable.ic_settings_google_account);
            } else {
                try {
                    authImage = targetContext.getDrawable(authDesc.iconId);
                } catch (Resources.NotFoundException e) {
                    Log.e(TAG, "Authenticator has bad resources", e);
                }
            }

            // Display an entry for each installed account we have.
            for (final Account account : accounts) {
                final Preference preference = new Preference(themedContext);
                preference.setTitle(authTitle != null ? authTitle : account.name);
                preference.setIcon(authImage);
                preference.setSummary(authTitle != null ? account.name : null);
                preference.setFragment(AccountSyncFragment.class.getName());
                AccountSyncFragment.prepareArgs(preference.getExtras(), account);

                mPersonalGroup.addPreference(preference);
            }
        }

        // Never allow restricted profile to add accounts.
        final Preference addAccountPref = findPreference(KEY_ADD_ACCOUNT);
        if (addAccountPref != null) {
            if (isRestricted()) {
                addAccountPref.setVisible(false);
            } else {
                // If there's already a Google account installed, disallow installing a second one.
                if (googleAccountCount > 0) {
                    allowableAccountTypes.remove(ACCOUNT_TYPE_GOOGLE);
                }

                // If there are available account types, add the "add account" button.
                Intent i = new Intent().setComponent(new ComponentName("com.android.tv.settings",
                        "com.android.tv.settings.accounts.AddAccountWithTypeActivity"));
                i.putExtra(AddAccountWithTypeActivity.EXTRA_ALLOWABLE_ACCOUNT_TYPES_STRING_ARRAY,
                        allowableAccountTypes.toArray(new String[allowableAccountTypes.size()]));

                addAccountPref.setVisible(!allowableAccountTypes.isEmpty());
                addAccountPref.setIntent(i);
            }
        }
    }

    private void updateAccessories() {
        if (mAccessoriesGroup == null) {
            return;
        }

        if (mBtAdapter == null) {
            mAccessoriesGroup.setVisible(false);
            mAccessoriesGroup.removeAll();
            return;
        }

        mAccessoriesGroup.removeAll();

        final Set<BluetoothDevice> bondedDevices = mBtAdapter.getBondedDevices();
        final Set<String> connectedBluetoothAddresses =
                BluetoothConnectionsManager.getConnectedSet(getContext());
        final Context themedContext = getPreferenceManager().getContext();

        for (final BluetoothDevice device : bondedDevices) {
            final String desc = connectedBluetoothAddresses.contains(device.getAddress())
                    ? getString(R.string.accessory_connected)
                    : null;

            final int deviceImgId = AccessoryUtils.getImageIdForDevice(device);
            final Preference preference = new Preference(themedContext);
            preference.setTitle(device.getName());
            preference.setSummary(desc);
            preference.setIcon(deviceImgId);
            preference.setFragment(BluetoothAccessoryFragment.class.getName());
            BluetoothAccessoryFragment.prepareArgs(
                    preference.getExtras(),
                    device.getAddress(),
                    device.getName(),
                    deviceImgId);
            mAccessoriesGroup.addPreference(preference);
        }
        if (mAddAccessory != null) {
            mAccessoriesGroup.addPreference(mAddAccessory);
        }
    }

    private void updateDeveloperOptions() {
        if (mDeveloperPref == null) {
            return;
        }

        final boolean developerEnabled = PreferenceUtils.isDeveloperEnabled(getContext());
        mDeveloperPref.setVisible(developerEnabled);
    }

    private void updateSounds() {
        if (mSoundsPref == null) {
            return;
        }

        mSoundsPref.setIcon(SoundFragment.getSoundEffectsEnabled(getContext().getContentResolver())
                ? R.drawable.settings_sound_on_icon : R.drawable.settings_sound_off_icon);
    }

    private void updateWifi() {
        if (mNetworkPref == null) {
            return;
        }

        mNetworkPref.setTitle(mConnectivityListener.isEthernetAvailable()
                ? R.string.connectivity_network : R.string.connectivity_wifi);

        // TODO: signal strength
    }

    public void updateGoogleSettings() {
        final Preference googleSettingsPref = findPreference(KEY_GOOGLE_SETTINGS);
        if (googleSettingsPref != null) {
            final ResolveInfo info = systemIntentIsHandled(googleSettingsPref.getIntent());
            googleSettingsPref.setVisible(info != null);
            if (info != null) {
                try {
                    final Context targetContext = getContext()
                            .createPackageContext(info.resolvePackageName != null ?
                                    info.resolvePackageName : info.activityInfo.packageName, 0);
                    googleSettingsPref.setIcon(targetContext.getDrawable(info.iconResourceId));
                } catch (Resources.NotFoundException | PackageManager.NameNotFoundException
                        | SecurityException e) {
                    Log.e(TAG, "Google settings icon not found", e);
                }
            }
        }
    }

    private ResolveInfo systemIntentIsHandled(Intent intent) {
        if (intent == null) {
            return null;
        }

        final PackageManager pm = getContext().getPackageManager();

        for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
            if (info.activityInfo != null && info.activityInfo.enabled &&
                    (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) ==
                            ApplicationInfo.FLAG_SYSTEM) {
                return info;
            }
        }
        return null;
    }
}
