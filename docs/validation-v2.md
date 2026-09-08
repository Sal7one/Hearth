# 0.1.1 investigation build

General model-file import now routes .zip files to the verified speech package importer, selects the imported engine and refreshes its model list. Folder import retains its separate path.

Local startup records package-verification / native-create stages and available RAM. The app can copy Android process-exit metadata and export the original retained Android trace bytes. Recoverable native linkage failures surface in the overlay. A native signal or low-memory termination is not claimed fixed without device evidence.

Gates passed: test (106 app tests in each variant; 48 shared-module tests in debug/release), compilePlayQaKotlin, compileFossQaKotlin, both QA assemblies, standalone APK/library/permission/16KB verification and cloud APK signature verification. No C++ or vendored runtime binary changed. Two new tests cover stage ordering and native-handle cleanup when a diagnostic callback fails after allocation.

Owner: reproduce on v2, reopen, Copy startup / crash report. Export Android crash trace if available. Confirm whether the original report was from Hearth v68 or Real time transiber v1.
