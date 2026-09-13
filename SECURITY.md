# Security

Report vulnerabilities without posting keys, recordings, transcripts, signed download
URLs or private crash traces in public issues. On GitHub, use **Security → Report a
vulnerability** when private reporting is enabled. If it is unavailable, open an
issue asking the maintainer for a private contact channel, without exploit details
or personal data. No private mailbox is advertised by this repository yet.

Include the app version/flavor, Android version, affected component, reproduction
steps using synthetic data, and expected versus observed behavior. Maintainership
currently targets the latest release; there is no promised response SLA or security
support for old APKs.

The foss flavor has no network permissions and disallows cloud clients, downloads
and network system voices. The play flavor permits explicit provider connections
and downloads. Model packages are untrusted input: retain size/path/hash validation
and native runtime isolation. Checksums establish integrity, not publisher trust.

Never place credentials or signing keys in Git, even briefly. If a real credential
was committed, revoke/rotate it first; removing the current file does not remove Git
history or downloaded copies. Release signing keys stay outside the repository.
See [privacy](PRIVACY.md) and [release checks](docs/releasing.md).
