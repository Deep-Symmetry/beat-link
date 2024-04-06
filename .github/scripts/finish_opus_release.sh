#!/usr/bin/env bash

set -e  # Exit if any command fails.

# See if we are creating a preview release
if [ "$release_snapshot" = true ] ; then

    # Update the release information on GitHub and reflect that it is ready.
    gh release edit latest-opus-preview --title "$release_tag (Opus) preview" --notes-file .github/resources/opus_preview_notes.md

else

    echo "We should not be here, only snapshots should be built on the opus branch!"
    exit 1

fi
