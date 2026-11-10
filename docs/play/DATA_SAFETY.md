# Play "Data safety" answers (draft)

Basis: Google counts data as "collected" only when it leaves the device to the developer or a third party through
the app. Data a user sends to websites they visit, through a browser, is not collected by the app developer.

- **Does your app collect or share any of the required user data types?** No.
  - Orbis sends nothing to its developers. Browsing data, passwords and settings stay on the device.
  - Cast sends the media address only to the TV or speaker the user picks on the local network. It does not use the
    Google Cast SDK, and it sends nothing to Google or any server. Casting YouTube opens that video in the TV's
    YouTube app, which can show ads and is subject to YouTube's own terms on the TV. That is the TV app, not Orbis.
  - Search suggestions go to the search engine the user picked as part of the user-initiated search feature (the
    user can turn them off); Google's guidance treats this as the user sending data to a service of their choice.
    If a reviewer disagrees, declare: "App activity > Other user-generated content: shared, optional, for app
    functionality" for search suggestions.
- **Is all data encrypted in transit?** Yes for Orbis's own requests (search suggestions, update check, add-on
  install all use HTTPS). Websites the user opens may use HTTP unless HTTPS-only mode is on.
- **Can users request that data be deleted?** Yes: all data is local; Settings > Clear browsing data, or uninstall.
- **Independent security review**: No.
