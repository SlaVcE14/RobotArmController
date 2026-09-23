package com.sj14apps.robotarmcontroller.about;

import android.content.ComponentName;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.sj14apps.robotarmcontroller.R;
import com.sj14apps.robotarmcontroller.databinding.ActivityAboutBinding;

import com.sjapps.library.customdialog.ImageListItem;
import com.sjapps.library.customdialog.ListDialog;

import java.util.ArrayList;

public class AboutActivity extends AppCompatActivity {

    private static final String SITE_APP_VERSIONS = "https://slavce.sj14apps.com";
    final String STORE_PACKAGE_NAME = "com.sjapps.sjstore";

    private ActivityAboutBinding binding;

    ArrayList<AboutListItem> appInfoItems = new ArrayList<>();
    ArrayList<AboutListItem> libsItems;
    boolean isStoreInstalled;
    Drawable storeIcon;
    String storeName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        initialize();
        setLayoutBounds();
        Animation animation = AnimationUtils.loadAnimation(this, com.sjapps.library.R.anim.slide_in);
        binding.nestedList.startAnimation(animation);
        PackageManager manager = getPackageManager();
        try {
            ApplicationInfo applicationInfo = manager.getApplicationInfo(getPackageName(), 0);
            binding.logo.setImageDrawable(applicationInfo.loadIcon(manager));

            String Name = (String) manager.getApplicationLabel(applicationInfo);
            String Version = manager.getPackageInfo(getPackageName(), 0).versionName;

            appInfoItems.add(new AboutListItem(getString(R.string.name), Name));
            appInfoItems.add(new AboutListItem(getString(R.string.version), Version));
            libsItems = new LibraryList().getItems(this);
            setupList(appInfoItems, binding.aboutList);
            setupList(libsItems, binding.LibrariesList);

        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void setLayoutBounds() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets insetsN = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());

            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) v.getLayoutParams();

            layoutParams.leftMargin = insets.left + insetsN.left;
            layoutParams.topMargin = insets.top;
            layoutParams.rightMargin = insets.right + insetsN.right;
            View scrollRL = binding.scrollRL;
            scrollRL.setPadding(scrollRL.getPaddingLeft(), scrollRL.getPaddingTop(), scrollRL.getPaddingRight(), insets.bottom + insetsN.bottom);
            v.setLayoutParams(layoutParams);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void setupList(ArrayList<AboutListItem> items, @NonNull RecyclerView view) {
        AboutListAdapter adapter = new AboutListAdapter(items);
        view.setNestedScrollingEnabled(false);
        view.setAdapter(adapter);
        view.setLayoutManager(new LinearLayoutManager(this));
    }

    void initialize() {
        if (CheckStoreIsInstalled()) {
            isStoreInstalled = true;
        }
    }

    public void CheckForUpdate(View view) {
        ListDialog dialog = new ListDialog();

        ArrayList<ImageListItem> items = new ArrayList<>();
//        items.add(new ImageListItem("Site", AppCompatResources.getDrawable(this, R.drawable.ic_globe), (ImageItemClick) this::openSite));

        if (isStoreInstalled) {
            items.add(new ImageListItem(storeName, storeIcon, (ImageItemClick) this::openStore));
        }


        dialog.Builder(this, true)
                .setTitle("Open...")
                .setImageItems(items, (position, obj) -> {
                    if (obj.getData() == null)
                        return;
                    ((ImageItemClick) obj.getData()).onClick();
                    dialog.dismiss();
                })
                .show();
    }

    private void openSite() {
        openLink(SITE_APP_VERSIONS);
    }

    private void openLink(String site) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(site));
        startActivity(intent);
    }

    private void openStore() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(STORE_PACKAGE_NAME, STORE_PACKAGE_NAME + ".AppActivity"));
        intent.putExtra("packageName", getPackageName());
        intent.putExtra("isInstalled", true);
        startActivity(intent);
    }

    private boolean CheckStoreIsInstalled() {
        PackageManager packageManager = getPackageManager();
        try {
            PackageInfo packageInfo = packageManager.getPackageInfo(STORE_PACKAGE_NAME, 0);
            storeIcon = packageInfo.applicationInfo.loadIcon(packageManager);
            storeName = packageInfo.applicationInfo.loadLabel(packageManager).toString();
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public void Back(View view) {
        finish();
    }


}