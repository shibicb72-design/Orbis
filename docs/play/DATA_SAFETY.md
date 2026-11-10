# Play "Data safety" answers (draft)

Basis: Google counts data as "collected" only when it leaves the device to the developer or a third party through
the app. Data a user sends to websites they visit, through a browser, is not collected by the app developer.

- **Does your app collect or share any of the required user data types?** Yes, because of the Google Cast SDK only.
  - Orbis itself still sends nothing to its developers. Browsing data, passwords and settings stay on the device.
  - `com.google.android.gms:play-services-cast` and `play-services-cast-framework` 23.0.0 send encrypted diagnostics to
    Google (discovery and session events, mobile device information, client app information). Google's disclosure
    (https://developers.google.com/cast/docs/android_sender/data_disclosure, updated 2026-11-09) says the logs are
    anonymous, not used to profile a user, not shared with other apps, and that neither the developer nor the user can
    opt out or delete them. Declare that collection from Google's page when filling the form. Also review
    `play-services-base` 18.11.0 and `play-services-basement` 18.12.0, which Cast pulls in and which were not in the
    app before: https://developers.google.com/android/guides/play-data-disclosure
  - The media address is sent only to the TV or speaker the user picks on the LAN. That is not collection by the developer.
  - Search suggestions go to the search engine the user picked as part of the user-initiated search feature (the
    user can turn them off); Google's guidance treats this as the user sending data to a service of their choice.
    If a reviewer disagrees, declare: "App activity > Other user-generated content: shared, optional, for app
    functionality" for search suggestions.
- **Is all data encrypted in transit?** Yes for Orbis's own requests (search suggestions, update check, add-on
  install all use HTTPS). Websites the user opens may use HTTP unless HTTPS-only mode is on.
- **Can users request that data be deleted?** Yes: all data is local; Settings > Clear browsing data, or uninstall.
- **Independent security review**: No.
