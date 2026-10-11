# Offline MFi Authentication Keys

This directory is used for local offline MFi (Made for iPhone) authentication between MeowPlay and connecting iOS devices.

## Required Files

To enable offline MFi authentication for Apple CarPlay, place the following two files in this folder:

1. **`identity.pk8`**
   - The MFi coprocessor private key in PKCS#8 DER format (e.g. extracted from an authorized adapter firmware or MFi auth chip).
2. **`certificate.p7b`**
   - The Apple MFi certificate bundle in PKCS#7 DER format.

When the application boots, `MeowPlayApplication` copies these credentials into the app's secure private storage directory and initializes `LocalMfiAuthenticationClient`.

## Legal Notice

Due to Apple intellectual property rights and licensing restrictions, MFi cryptographic certificates and private keys **cannot** be bundled or distributed within this repository. 

Both `*.p7b` and `*.pk8` files are explicitly excluded via `.gitignore` to prevent accidental commits.
