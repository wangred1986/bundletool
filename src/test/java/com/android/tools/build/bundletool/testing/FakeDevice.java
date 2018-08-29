/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tools.build.bundletool.testing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.android.bundle.Devices.DeviceSpec;
import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice.DeviceState;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.android.sdklib.AndroidVersion;
import com.android.tools.build.bundletool.device.Device;
import com.android.tools.build.bundletool.utils.Versions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Fake implementation of {@link Device} for tests. */
public class FakeDevice implements Device {

  private final DeviceState state;
  private final AndroidVersion androidVersion;
  private final ImmutableList<String> abis;
  private final int density;
  private final String serialNumber;
  private final ImmutableMap<String, String> properties;
  private final Map<String, FakeShellCommandAction> commandInjections = new HashMap<>();
  private Optional<SideEffect> installApksSideEffect = Optional.empty();

  FakeDevice(
      String serialNumber,
      DeviceState state,
      int sdkVersion,
      ImmutableList<String> abis,
      int density,
      ImmutableMap<String, String> properties) {
    this.state = state;
    this.androidVersion = new AndroidVersion(sdkVersion);
    this.abis = abis;
    this.density = density;
    this.serialNumber = serialNumber;
    this.properties = properties;
  }

  public static FakeDevice fromDeviceSpecWithProperties(
      String deviceId,
      DeviceState deviceState,
      DeviceSpec deviceSpec,
      ImmutableMap<String, String> properties) {
    return new FakeDevice(
        deviceId,
        deviceState,
        deviceSpec.getSdkVersion(),
        ImmutableList.copyOf(deviceSpec.getSupportedAbisList()),
        deviceSpec.getScreenDensity(),
        properties);
  }

  public static FakeDevice fromDeviceSpec(
      String deviceId, DeviceState deviceState, DeviceSpec deviceSpec) {
    if (deviceSpec.getSdkVersion() < Versions.ANDROID_M_API_VERSION) {
      Locale deviceLocale = Locale.forLanguageTag(deviceSpec.getSupportedLocales(0));
      return fromDeviceSpecWithProperties(
          deviceId,
          deviceState,
          deviceSpec,
          ImmutableMap.of(
              "ro.product.locale.language",
              deviceLocale.getLanguage(),
              "ro.product.locale.region",
              deviceLocale.getCountry()));
    } else {
      return fromDeviceSpecWithProperties(
          deviceId,
          deviceState,
          deviceSpec,
          ImmutableMap.of("ro.product.locale", deviceSpec.getSupportedLocales(0)));
    }
  }

  public static FakeDevice inDisconnectedState(String deviceId, DeviceState deviceState) {
    checkArgument(deviceState != DeviceState.ONLINE);
    // In this state, querying device doesn't work.
    return new FakeDevice(
        deviceId,
        deviceState,
        Integer.MAX_VALUE,
        ImmutableList.of(),
        /* density= */ -1,
        ImmutableMap.of());
  }

  @Override
  public DeviceState getState() {
    return state;
  }

  @Override
  public AndroidVersion getVersion() {
    return androidVersion;
  }

  @Override
  public ImmutableList<String> getAbis() {
    return abis;
  }

  @Override
  public int getDensity() {
    return density;
  }

  @Override
  public String getSerialNumber() {
    return serialNumber;
  }

  @Override
  public Optional<String> getProperty(String propertyName) {
    return Optional.ofNullable(properties.get(propertyName));
  }

  @Override
  public void executeShellCommand(
      String command,
      IShellOutputReceiver receiver,
      long maxTimeToOutputResponse,
      TimeUnit maxTimeUnits)
      throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
          IOException {
    checkState(commandInjections.containsKey(command));
    byte[] data = commandInjections.get(command).onExecute().getBytes(UTF_8);
    receiver.addOutput(data, 0, data.length);
    receiver.flush();
  }

  @Override
  public void installApks(
      ImmutableList<Path> apks, boolean reinstall, long timeout, TimeUnit timeoutUnit) {
    installApksSideEffect.ifPresent(val -> val.apply(apks, reinstall));
  }

  public void setInstallApksSideEffect(SideEffect sideEffect) {
    installApksSideEffect = Optional.of(sideEffect);
  }

  public void clearInstallApksSideEffect() {
    installApksSideEffect = Optional.empty();
  }

  public void injectShellCommandOutput(String command, FakeShellCommandAction action) {
    checkState(!commandInjections.containsKey(command));
    commandInjections.put(command, action);
  }

  /** Fake shell command action. */
  @FunctionalInterface
  public interface FakeShellCommandAction {
    String onExecute()
        throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
            IOException;
  }

  /** Side effect. */
  public interface SideEffect {
    void apply(ImmutableList<Path> apks, boolean reinstall);
  }
}