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
  - Torrent downloads (a magnet link or .torrent file the user confirms) connect directly to other people sharing
    that torrent (peers), to the torrent's trackers, to DHT bootstrap nodes and, only if the user turns local peer
    discovery on (off by default), to devices on the same network. A private tab's torrents use their trackers
    only (no DHT, peer exchange or local peer discovery), and WebSocket (WebTorrent) trackers are never contacted. All of them can see the phone's IP address, as for any peer-to-peer download; the
    pieces of the file are exchanged with peers. Nothing else leaves the device: no account, no analytics, nothing to
    the developers. The torrent list stays on the phone (a private tab's torrents are never written to the list on
    disk; their files stay inside the app and are deleted when private browsing ends unless the user saved them to
    Downloads, as for a private tab's file downloads). This is the user exchanging a file with peers they chose, as when visiting a website, so no data type is
    declared. Play's form has no data type for "IP address seen by peers"; answer No and keep this note for a review.
  - Seeding (uploading pieces of a finished torrent to other people) happens only when the user turns "Stop seeding
    when finished" off in Settings > Torrents; by default nothing is uploaded once a download is finished. DHT and
    peer exchange can each be turned off there, and local peer discovery turned on. Speed limits and "Wi-Fi only" are local
    settings: they stay on the phone and change nothing that is sent. None of this changes the answers above.
- **Is all data encrypted in transit?** Yes for Orbis's own requests (search suggestions and add-on
  install use HTTPS). Websites the user opens may use HTTP unless HTTPS-only mode is on. Torrent traffic with peers
  and trackers uses the BitTorrent protocols; peers may or may not support encryption.
- **Can users request that data be deleted?** Yes: all data is local; Settings > Clear browsing data, or uninstall.
- **Independent security review**: No.
