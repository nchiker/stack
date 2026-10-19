# Open-source scanner references

Reviewed upstream documentation and build files on 2026-10-16, following the same-page Google Stack comparison. These are source-review candidates, not measured quality or compatibility results. No package was downloaded, added or copied into this application during this review.

## Shortlist

| Project | Useful reference | License / integration observations |
| --- | --- | --- |
| [OSS Document Scanner](https://github.com/ossappscollective/OSS-DocumentScanner) | Its [editing documentation](https://ossdocumentscanner.akylas.fr/edit-and-enhance) describes perspective correction, grayscale/binary/color modes, shadow removal, illumination correction and background cleanup. The repository separates C++ processing and native processor integration. Highest-priority processing reference for this project. | Root [MIT license](https://github.com/ossappscollective/OSS-DocumentScanner/blob/main/LICENSE). This is a complete NativeScript application using OpenCV and Tesseract, not a small Java capture dependency. Its documented full build requires a substantially larger environment than ours. Review individual processing files and dependencies before any reuse. |
| [OpenCV](https://opencv.org/) | Independently available image-processing primitives: [perspective warping](https://docs.opencv.org/4.x/da/d54/group__imgproc__transform.html), [thresholding](https://docs.opencv.org/4.x/d7/d4d/tutorial_py_thresholding.html), contours, filtering and morphology. A candidate foundation for capture-independent page processing. | [Apache 2.0 from 4.5.0 onward](https://opencv.org/license/); earlier versions use BSD 3-Clause. It is not a complete document scanner or a quality guarantee. A bundled artifact could preserve offline operation, but its ABI coverage, native-library alignment, size, memory and build cost must be checked on the selected version. |
| [ScanLibrary / AndroidScannerDemo](https://github.com/jhansireddy/AndroidScannerDemo) | Focused Android example of four-corner cropping and perspective transformation using OpenCV. Useful for understanding a small scanning workflow. | README includes an MIT license. Its integration instructions use the legacy Gradle `compile` configuration and imported modules. Treat it as a reference requiring modernization, not a dependency ready for our current toolchain. |
| [CVScanner](https://github.com/hannesa2/CVScanner) | Android crop/scan library using OpenCV; useful native Android reference. | Apache 2.0. Its current [library build file](https://github.com/hannesa2/CVScanner/blob/master/library/build.gradle) declares Kotlin/AndroidX, OpenCV, Google Play Services Vision/basement and AndroidVisionPipeline. That dependency graph does not match our current minimal, Google-independent baseline. |

The Zynkware [Document Scanning Android SDK](https://github.com/zynkware/Document-Scanning-Android-SDK) also demonstrates CameraX live detection, automatic capture, flash and editable quadrilaterals with a reduced OpenCV build. Its [build file](https://github.com/zynkware/Document-Scanning-Android-SDK/blob/master/DocumentScanner/build.gradle) brings several additional dependencies. A clear project-wide redistribution license was not established in this review; do not adopt its source or artifact on the basis of public repository access alone.

Google's ML Kit document scanner is not suitable for our first-use offline contract: [Google's documentation](https://developers.google.com/ml-kit/vision/doc-scanner/android) states that its models, scanner logic and UI are downloaded dynamically by Google Play services. An open-source wrapper around that API would not remove this dependency.

## Recommended next step

Study OSS Document Scanner's processing sequence and distinguish capture, geometry correction, illumination normalization, tone mapping and sharpening. Review the actual implementation before assuming its documented filters solve our examples. Keep our durable originals, editable page state and Android library workflow.

Then make a bounded decision between independently implementing the useful techniques and bundling a pinned OpenCV processing dependency. A small dependency set is a goal; a handwritten detector that repeatedly misses the page has a maintenance cost too. Compare the same original images, fixed output sizes and manual reference corners before judging quality. Record crop error, legibility, faint-detail retention, latency, memory, APK size and first-use offline behavior. No such comparative benchmark was run for this review.

Do not replace the application framework or pull OCR into the scanner decision. Curved-page flattening remains separate from a four-corner perspective transform. Any future source reuse must preserve the applicable licenses/notices; Google code/assets remain excluded.

## Source-level follow-up

The 0.4.2-dev pass reviewed actual processing files at pinned revisions, including file-level provenance and dependency boundaries. See [implementation research](STACK_SCANNER_RESEARCH.md). The decision was to keep the current platform pipeline with independently authored fixes; no scanner/OpenCV artifact was integrated. [Fixed-source evidence](STACK_SCANNER_QUALITY.md) records crop/cleanup improvements, the user's requested gentle-curve flattening, costs and remaining limits. This supersedes the pending-research recommendation above; it does not establish measured quality of any shortlisted app.
