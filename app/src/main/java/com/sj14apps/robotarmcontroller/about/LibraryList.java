package com.sj14apps.robotarmcontroller.about;

import com.sjapps.library.BuildConfig;

public class LibraryList extends ListGenerator{
    @Override
    public void init() {
        addItem("SJ Dialog", BuildConfig.VERSION_NAME, "https://github.com/SlaVcE14/SJ-Dialog");
        addItem("BLECtrl", "1.0", "https://github.com/SlaVcE14/BLECtrl");
    }
}

