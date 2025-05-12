package com.example.meshup.ui.logout;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class LogoutViewModel extends ViewModel {

    private final MutableLiveData<String> mText;
    private final MutableLiveData<Boolean> isLoggingOut;

    public LogoutViewModel() {
        mText = new MutableLiveData<>();
        mText.setValue("Are you sure you want to logout?");
        isLoggingOut = new MutableLiveData<>();
        isLoggingOut.setValue(false);
    }

    public LiveData<String> getText() {
        return mText;
    }

    public LiveData<Boolean> getIsLoggingOut() {
        return isLoggingOut;
    }

    public void setLoggingOut(boolean loggingOut) {
        isLoggingOut.setValue(loggingOut);
    }
}