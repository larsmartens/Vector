/*
 * <!--This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 LSPosed Contributors-->
 */

package org.lsposed.manager.ui.fragment;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AlertDialog;
import androidx.core.text.HtmlCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.DynamicColors;

import org.lsposed.manager.App;
import org.lsposed.manager.BuildConfig;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.databinding.DialogIgnoredModuleUpdatesBinding;
import org.lsposed.manager.databinding.FragmentSettingsBinding;
import org.lsposed.manager.databinding.ItemIgnoredModuleUpdateBinding;
import org.lsposed.manager.repo.RepoLoader;
import org.lsposed.manager.ui.activity.MainActivity;
import org.lsposed.manager.ui.dialog.BlurBehindDialogBuilder;
import org.lsposed.manager.util.BackupUtils;
import org.lsposed.manager.util.CloudflareDNS;
import org.lsposed.manager.util.LangList;
import org.lsposed.manager.util.ModuleUtil;
import org.lsposed.manager.util.NavUtil;
import org.lsposed.manager.util.ShortcutUtil;
import org.lsposed.manager.util.ThemeUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import rikka.core.util.ResourceUtils;
import rikka.material.app.LocaleDelegate;
import rikka.material.preference.MaterialSwitchPreference;
import rikka.preference.SimpleMenuPreference;
import rikka.recyclerview.RecyclerViewKt;
import rikka.widget.borderview.BorderRecyclerView;

public class SettingsFragment extends BaseFragment {
    FragmentSettingsBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        binding.appBar.setLiftable(true);
        setupToolbar(binding.toolbar, binding.clickView, R.string.Settings);
        binding.toolbar.setNavigationIcon(null);
        if (savedInstanceState == null) {
            getChildFragmentManager().beginTransaction().add(R.id.setting_container, new PreferenceFragment()).commitNow();
        }
        if (ConfigManager.isBinderAlive()) {
            binding.toolbar.setSubtitle(String.format(LocaleDelegate.getDefaultLocale(), "%s (%d)", ConfigManager.getXposedVersionName(), ConfigManager.getXposedVersionCode()));
        } else {
            binding.toolbar.setSubtitle(String.format(LocaleDelegate.getDefaultLocale(), "%s (%d) - %s", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, getString(R.string.not_installed)));
        }
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        binding = null;
    }

    public static class PreferenceFragment extends PreferenceFragmentCompat {
        private SettingsFragment parentFragment;

        ActivityResultLauncher<String> backupLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/gzip"), uri -> {
            if (uri == null || parentFragment == null) return;
            parentFragment.runAsync(() -> {
                try {
                    BackupUtils.backup(uri);
                } catch (Exception e) {
                    var text = App.getInstance().getString(R.string.settings_backup_failed2, e.getMessage());
                    parentFragment.showHint(text, false);
                }
            });
        });
        ActivityResultLauncher<String[]> restoreLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || parentFragment == null) return;
            parentFragment.runAsync(() -> {
                try {
                    BackupUtils.restore(uri);
                } catch (Exception e) {
                    var text = App.getInstance().getString(R.string.settings_restore_failed2, e.getMessage());
                    parentFragment.showHint(text, false);
                }
            });
        });

        @Override
        public void onAttach(@NonNull Context context) {
            super.onAttach(context);

            parentFragment = (SettingsFragment) requireParentFragment();
        }

        @Override
        public void onDetach() {
            super.onDetach();

            parentFragment = null;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final String SYSTEM = "SYSTEM";

            addPreferencesFromResource(R.xml.prefs);

            boolean installed = ConfigManager.isBinderAlive();
            MaterialSwitchPreference prefVerboseLogs = findPreference("disable_verbose_log");
            if (prefVerboseLogs != null) {
                prefVerboseLogs.setEnabled(!BuildConfig.DEBUG && installed);
                if (BuildConfig.DEBUG) ConfigManager.setVerboseLogEnabled(false);
                prefVerboseLogs.setChecked(!installed || !ConfigManager.isVerboseLogEnabled());
                prefVerboseLogs.setOnPreferenceChangeListener((preference, newValue) -> ConfigManager.setVerboseLogEnabled(!(boolean) newValue));
            }

            MaterialSwitchPreference notificationPreference = findPreference("enable_status_notification");
            if (notificationPreference != null) {
                notificationPreference.setVisible(installed);
                if (installed) {
                    notificationPreference.setChecked(ConfigManager.enableStatusNotification());
                    notificationPreference.setSummaryOn(R.string.settings_enable_status_notification_summary);
                    notificationPreference.setEnabled(true);
                }
                notificationPreference.setOnPreferenceChangeListener((p, v) ->
                    ConfigManager.setEnableStatusNotification((boolean) v)
                );
            }

            Preference shortcut = findPreference("add_shortcut");
            if (shortcut != null) {
                shortcut.setVisible(App.isParasitic);
                if (!ShortcutUtil.isRequestPinShortcutSupported(requireContext())) {
                    shortcut.setEnabled(false);
                    shortcut.setSummary(R.string.settings_unsupported_pin_shortcut_summary);
                }
                shortcut.setOnPreferenceClickListener(preference -> {
                    if (!ShortcutUtil.requestPinLaunchShortcut(() -> {
                        App.getPreferences().edit().putBoolean("never_show_welcome", true).apply();
                        parentFragment.showHint(R.string.settings_shortcut_pinned_hint, false);
                    })) {
                        parentFragment.showHint(R.string.settings_unsupported_pin_shortcut_summary, true);
                    }
                    return true;
                });
            }

            Preference backup = findPreference("backup");
            if (backup != null) {
                backup.setEnabled(installed);
                backup.setOnPreferenceClickListener(preference -> {
                    LocalDateTime now = LocalDateTime.now();
                    try {
                        backupLauncher.launch(String.format(LocaleDelegate.getDefaultLocale(), "Vector_%s.lsp", now.toString()));
                        return true;
                    } catch (ActivityNotFoundException e) {
                        parentFragment.showHint(R.string.enable_documentui, true);
                        return false;
                    }
                });
            }

            Preference restore = findPreference("restore");
            if (restore != null) {
                restore.setEnabled(installed);
                restore.setOnPreferenceClickListener(preference -> {
                    try {
                        restoreLauncher.launch(new String[]{"*/*"});
                        return true;
                    } catch (ActivityNotFoundException e) {
                        parentFragment.showHint(R.string.enable_documentui, true);
                        return false;
                    }
                });
            }

            Preference theme = findPreference("dark_theme");
            if (theme != null) {
                theme.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (!App.getPreferences().getString("dark_theme", ThemeUtil.MODE_NIGHT_FOLLOW_SYSTEM).equals(newValue)) {
                        AppCompatDelegate.setDefaultNightMode(ThemeUtil.getDarkTheme((String) newValue));
                    }
                    return true;
                });
            }

            Preference black_dark_theme = findPreference("black_dark_theme");
            if (black_dark_theme != null) {
                black_dark_theme.setOnPreferenceChangeListener((preference, newValue) -> {
                    MainActivity activity = (MainActivity) getActivity();
                    if (activity != null && ResourceUtils.isNightMode(getResources().getConfiguration())) {
                        activity.restart();
                    }
                    return true;
                });
            }

            Preference primary_color = findPreference("theme_color");
            if (primary_color != null) {
                primary_color.setOnPreferenceChangeListener((preference, newValue) -> {
                    MainActivity activity = (MainActivity) getActivity();
                    if (activity != null) {
                        activity.restart();
                    }
                    return true;
                });
            }

            MaterialSwitchPreference prefShowHiddenIcons = findPreference("show_hidden_icon_apps_enabled");
            if (prefShowHiddenIcons != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ConfigManager.isBinderAlive()) {
                    prefShowHiddenIcons.setEnabled(true);
                    prefShowHiddenIcons.setOnPreferenceChangeListener((preference, newValue) -> ConfigManager.setHiddenIcon(!(boolean) newValue));
                }
                prefShowHiddenIcons.setChecked(Settings.Global.getInt(requireActivity().getContentResolver(), "show_hidden_icon_apps_enabled", 1) != 0);
            }

            MaterialSwitchPreference prefFollowSystemAccent = findPreference("follow_system_accent");
            if (prefFollowSystemAccent != null && DynamicColors.isDynamicColorAvailable()) {
                if (primary_color != null) {
                    primary_color.setVisible(!prefFollowSystemAccent.isChecked());
                }
                prefFollowSystemAccent.setVisible(true);
                prefFollowSystemAccent.setOnPreferenceChangeListener((preference, newValue) -> {
                    MainActivity activity = (MainActivity) getActivity();
                    if (activity != null) {
                        activity.restart();
                    }
                    return true;
                });
            }

            MaterialSwitchPreference prefDoH = findPreference("doh");
            if (prefDoH != null) {
                var dns = (CloudflareDNS) App.getOkHttpClient().dns();
                if (!dns.noProxy) {
                    prefDoH.setEnabled(false);
                    prefDoH.setVisible(false);
                    var group = prefDoH.getParent();
                    assert group != null;
                    group.setVisible(false);
                }
                prefDoH.setOnPreferenceChangeListener((p, v) -> {
                    dns.DoH = (boolean) v;
                    return true;
                });
            }

            SimpleMenuPreference language = findPreference("language");
            if (language != null) {
                var tag = language.getValue();
                var userLocale = App.getLocale();
                var entries = new ArrayList<CharSequence>();
                var lstLang = LangList.LOCALES;
                for (var lang : lstLang) {
                    if (lang.equals(SYSTEM)) {
                        entries.add(getString(rikka.core.R.string.follow_system));
                        continue;
                    }
                    var locale = Locale.forLanguageTag(lang);
                    entries.add(HtmlCompat.fromHtml(locale.getDisplayName(locale), HtmlCompat.FROM_HTML_MODE_LEGACY));
                }
                language.setEntries(entries.toArray(new CharSequence[0]));
                language.setEntryValues(lstLang);
                if (TextUtils.isEmpty(tag) || SYSTEM.equals(tag)) {
                    language.setSummary(getString(rikka.core.R.string.follow_system));
                } else {
                    var locale = Locale.forLanguageTag(tag);
                    language.setSummary(!TextUtils.isEmpty(locale.getScript()) ? locale.getDisplayScript(userLocale) : locale.getDisplayName(userLocale));
                }
                language.setOnPreferenceChangeListener((preference, newValue) -> {
                    var app = App.getInstance();
                    var locale = App.getLocale((String) newValue);
                    var res = app.getResources();
                    var config = res.getConfiguration();
                    config.setLocale(locale);
                    LocaleDelegate.setDefaultLocale(locale);
                    //noinspection deprecation
                    res.updateConfiguration(config, res.getDisplayMetrics());
                    MainActivity activity = (MainActivity) getActivity();
                    if (activity != null) {
                        activity.restart();
                    }
                    return true;
                });
            }

            Preference translation = findPreference("translation");
            if (translation != null) {
                translation.setOnPreferenceClickListener(preference -> {
                    NavUtil.startURL(requireActivity(), "https://crowdin.com/project/lsposed_jingmatrix");
                    return true;
                });
                translation.setSummary(getString(R.string.settings_translation_summary, getString(R.string.app_name)));
            }

            Preference translation_contributors = findPreference("translation_contributors");
            if (translation_contributors != null) {
                var translators = HtmlCompat.fromHtml(getString(R.string.translators), HtmlCompat.FROM_HTML_MODE_LEGACY);
                if (translators.toString().equals("null")) {
                    translation_contributors.setVisible(false);
                } else {
                    translation_contributors.setSummary(translators);
                }
            }
            SimpleMenuPreference channel = findPreference("update_channel");
            if (channel != null) {
                channel.setOnPreferenceChangeListener((preference, newValue) -> {
                    var repoLoader = RepoLoader.getInstance();
                    repoLoader.updateLatestVersion(String.valueOf(newValue));
                    return true;
                });
            }
            Preference ignoredModuleUpdates = findPreference("ignored_module_updates");
            if (ignoredModuleUpdates != null) {
                ignoredModuleUpdates.setOnPreferenceClickListener(preference -> {
                    showIgnoredModuleUpdatesDialog();
                    return true;
                });
            }
        }

        private void showIgnoredModuleUpdatesDialog() {
            var items = buildIgnoredModuleUpdateItems();
            if (items.isEmpty()) {
                parentFragment.showHint(R.string.settings_no_ignored_module_updates, false);
                return;
            }
            var binding = DialogIgnoredModuleUpdatesBinding.inflate(getLayoutInflater());
            binding.title.setText(R.string.settings_ignored_module_updates);
            binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
            var layoutParams = binding.recyclerView.getLayoutParams();
            int itemHeight = (int) (getResources().getDisplayMetrics().density * 72);
            int maxHeight = (int) (getResources().getDisplayMetrics().density * 360);
            layoutParams.height = Math.min(maxHeight, items.size() * itemHeight);
            binding.recyclerView.setLayoutParams(layoutParams);
            var dialog = new BlurBehindDialogBuilder(requireActivity())
                    .setView(binding.getRoot())
                    .create();
            binding.recyclerView.setAdapter(new IgnoredModuleUpdatesAdapter(items, dialog));
            binding.cancel.setOnClickListener(v -> dialog.dismiss());
            binding.title.setOnClickListener(v -> binding.recyclerView.smoothScrollToPosition(0));
            dialog.show();
        }

        private ArrayList<IgnoredModuleUpdateItem> buildIgnoredModuleUpdateItems() {
            var ignoredPackages = ModuleUtil.getIgnoredModuleUpdates();
            var items = new ArrayList<IgnoredModuleUpdateItem>(ignoredPackages.size());
            PackageManager packageManager = requireContext().getPackageManager();
            Drawable defaultIcon = packageManager.getDefaultActivityIcon();
            var installedModules = new HashMap<String, ModuleUtil.InstalledModule>();
            var modules = ModuleUtil.getInstance().getModules();
            if (modules != null) {
                modules.values().forEach(module -> installedModules.putIfAbsent(module.packageName, module));
            }
            ignoredPackages.stream().sorted().forEach(packageName -> {
                var module = installedModules.get(packageName);
                if (module != null) {
                    items.add(new IgnoredModuleUpdateItem(
                            module.getAppName(),
                            module.packageName,
                            module.app.loadIcon(packageManager),
                            packageName
                    ));
                } else {
                    items.add(new IgnoredModuleUpdateItem(
                            packageName,
                            getString(R.string.module_not_installed),
                            defaultIcon,
                            packageName
                    ));
                }
            });
            return items;
        }

        private static class IgnoredModuleUpdateItem {
            private final String appName;
            private final String packageName;
            private final Drawable icon;
            private final String ignoredPackageName;

            private IgnoredModuleUpdateItem(String appName, String packageName, Drawable icon, String ignoredPackageName) {
                this.appName = appName;
                this.packageName = packageName;
                this.icon = icon;
                this.ignoredPackageName = ignoredPackageName;
            }
        }

        private class IgnoredModuleUpdatesAdapter extends RecyclerView.Adapter<IgnoredModuleUpdatesAdapter.ViewHolder> {
            private final ArrayList<IgnoredModuleUpdateItem> items;
            private final AlertDialog dialog;

            private IgnoredModuleUpdatesAdapter(ArrayList<IgnoredModuleUpdateItem> items, AlertDialog dialog) {
                this.items = items;
                this.dialog = dialog;
            }

            @NonNull
            @Override
            public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new ViewHolder(ItemIgnoredModuleUpdateBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                var item = items.get(position);
                holder.binding.appName.setText(item.appName);
                holder.binding.packageName.setText(item.packageName);
                holder.binding.icon.setImageDrawable(item.icon);
                holder.binding.remove.setOnClickListener(v -> {
                    int adapterPosition = holder.getBindingAdapterPosition();
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return;
                    }
                    var removed = items.remove(adapterPosition);
                    ModuleUtil.setUpdateIgnored(removed.ignoredPackageName, false);
                    notifyItemRemoved(adapterPosition);
                    if (items.isEmpty()) {
                        dialog.dismiss();
                        parentFragment.showHint(R.string.settings_no_ignored_module_updates, false);
                    }
                });
            }

            @Override
            public int getItemCount() {
                return items.size();
            }

            class ViewHolder extends RecyclerView.ViewHolder {
                private final ItemIgnoredModuleUpdateBinding binding;

                private ViewHolder(ItemIgnoredModuleUpdateBinding binding) {
                    super(binding.getRoot());
                    this.binding = binding;
                }
            }
        }

        @NonNull
        @Override
        public RecyclerView onCreateRecyclerView(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent, Bundle savedInstanceState) {
            BorderRecyclerView recyclerView = (BorderRecyclerView) super.onCreateRecyclerView(inflater, parent, savedInstanceState);
            RecyclerViewKt.fixEdgeEffect(recyclerView, false, true);
            recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener((top, oldTop, bottom, oldBottom) -> parentFragment.binding.appBar.setLifted(!top));
            var fragment = getParentFragment();
            if (fragment instanceof SettingsFragment settingsFragment) {
                View.OnClickListener l = v -> {
                    settingsFragment.binding.appBar.setExpanded(true, true);
                    recyclerView.smoothScrollToPosition(0);
                };
                settingsFragment.binding.toolbar.setOnClickListener(l);
                settingsFragment.binding.clickView.setOnClickListener(l);
            }
            return recyclerView;
        }
    }
}
