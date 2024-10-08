package com.kokio.IdentityManager;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowMetrics;
import androidx.core.app.ActivityCompat;
import androidx.core.util.Pair;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class identityManagerModule extends ReactContextBaseJavaModule {

  private final static String TAG = identityManagerModule.class.getCanonicalName();
  private final static String E_NO_DEFAULT_SUBSCRIPTION = "no_defalut_subscription";
  private final static String E_NO_PHONE_STATE_PERMISSION = "no_phoneState_permission_available";
  private final static String E_FAILED_IDENTITY_GENERATION = "identity_generation_failed";
  private final static String E_UNSUPPORTED_API_LEVEL = "android_version_unsupported";
  private static ReactApplicationContext mReactContext;
  private TelephonyManager mTelephonyManager;
  private SubscriptionManager mSubscriptionManager;

  identityManagerModule(ReactApplicationContext reactContext) {
    super(reactContext);
    mTelephonyManager = (TelephonyManager) reactContext.getSystemService(Context.TELEPHONY_SERVICE);

    mReactContext = reactContext;
    initSubscriptionManager();
  }

  private void initSubscriptionManager() {
    if (mSubscriptionManager == null) {
      mSubscriptionManager = (SubscriptionManager) mReactContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }
  }

  @Override
  public String getName() {
    return "identityManagerModule"; // Name exposed to React Native
  }

  private long getUnixTimestamp(){
    return Instant.now().getEpochSecond();
  }

  //Function to get screen resolution, returns a Pair<>(height, width). The values are integers 
  private Pair<Integer, Integer> getScreenResolution(){
    int width, height;
    WindowManager mWindowManager = (WindowManager) mReactContext.getSystemService(Context.WINDOW_SERVICE);
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      // For Android 11 (API Level 30) and higher
      WindowMetrics display = mWindowManager.getCurrentWindowMetrics();
      width = display.getBounds().width();
      height = display.getBounds().height();
    } else {
      // For older Android versions
      Display display = mWindowManager.getDefaultDisplay();
      Point size = new Point();
      display.getSize(size);
      width = size.x;
      height = size.y;
    }
    // Ensure vertical orientation format
    if (width > height) {
      int temp = width;
      width = height;
      height = temp;
    }
    return new Pair<>(height, width);
  }

  private String bytesToHex(byte[] bytes) {
    StringBuilder hexString = new StringBuilder();
    for (byte b : bytes) {
      String hex = Integer.toHexString(0xff & b);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }

  private String hash(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashBytes = digest.digest(input.getBytes());
      return bytesToHex(hashBytes);
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
      return null;
    }
  }

  //Getting the Phone number for default Subscription
  @ReactMethod 
  public void getDefaultPhoneNumber(Promise promise) {
    if (ActivityCompat.checkSelfPermission(mReactContext, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
      initSubscriptionManager();
      int defaultSubscriptionID = mSubscriptionManager.getDefaultSubscriptionId();
      SubscriptionInfo defaultSubscription = mSubscriptionManager.getActiveSubscriptionInfo(defaultSubscriptionID);
      if (defaultSubscription != null) {
        String phoneNumber;
        int subscriptionId = defaultSubscription.getSubscriptionId();
        // getPhoneNumber() method only works for android 13 and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          phoneNumber = mSubscriptionManager.getPhoneNumber(subscriptionId);
        } else {
          phoneNumber = mTelephonyManager.getLine1Number();
        }
        promise.resolve(phoneNumber);
      } else {
        promise.reject(E_NO_DEFAULT_SUBSCRIPTION, "Default Subscription is null"); // Promise reject if no default subscription
      }
    } else {
      promise.reject(E_NO_PHONE_STATE_PERMISSION, "READ_PHONE_STATE permission not granted");
    }
  }

  @ReactMethod
  public void getAndroidID(Promise promise) {
    if (ActivityCompat.checkSelfPermission(mReactContext, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
      try {
        String androidID = Settings.Secure.getString(mReactContext.getContentResolver(), Settings.Secure.ANDROID_ID);
        promise.resolve(androidID);
      } catch (Exception e) {
        promise.reject(TAG, "Could not get Android ID" + e.getMessage());
      }
    } else {
      promise.reject(E_NO_PHONE_STATE_PERMISSION, "READ_PHONE_STATE permission not granted");
    }
  }

  //@TODO Identity generation should be by device attestaion algorithm (using EID) in production code
  //Blocked on Carrier privileges and eSIM entitlement access in Android and iOS
  @ReactMethod
  public void generateIdentifier(String androidID, Promise promise) {
    // Get system time
    long timestamp = getUnixTimestamp();

    // Get hardware identifiers
    Pair<Integer,Integer> screenResolution = getScreenResolution();
    int height = screenResolution.first;
    int width = screenResolution.second;

    // Generate random bytes using CSPRNG
    SecureRandom random = new SecureRandom();
    byte[] randomBytes = new byte[16]; // 128 bits
    random.nextBytes(randomBytes);

    // Combine entropy: system time, hardware identifiers, and random bytes
    String combinedEntropy = timestamp + height + width + androidID + bytesToHex(randomBytes);

    // Apply hash function to the combined entropy
    String identifier = hash(combinedEntropy);

    // Use emit method in the future rather than prmoises
    // getReactApplicationContext().getJSModule(identityManagerModule.RCTDeviceEventEmitter.class).emit("IdentifierGenerated", identifier);
    if(identifier != null && identifier != ""){
      promise.resolve(identifier);
    } else {
      promise.reject(E_FAILED_IDENTITY_GENERATION, "Identity generation method did not work as expected");
    }
  }
}
