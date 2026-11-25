#!/usr/bin/env bash
set -euo pipefail

tag=${1:?version tag required}
commit=${2:?tag commit required}
apks=${3:?artifact directory required}
notes=${4:?release notes file required}
repo=${GITHUB_REPOSITORY:?GitHub repository required}
stable_tag='^v[0-9]+\.[0-9]+(\.[0-9]+)?$'
[[ "$tag" =~ $stable_tag && "$commit" =~ ^[a-f0-9]{40}$ ]]

die() { printf '%s\n' "$*" >&2; exit 1; }
remote_tip() {
    local name=$1 refs
    refs="$(git ls-remote --exit-code origin "refs/tags/$name" "refs/tags/$name^{}")" || return
    awk -v ref="refs/tags/$name" '
        $2 == ref { direct = $1 }
        $2 == ref "^{}" { peeled = $1 }
        END { print peeled ? peeled : direct }
    ' <<< "$refs"
}
verify_tag() {
    [[ "$(remote_tip "$tag")" == "$commit" ]] ||
        die "Tag $tag no longer points to the built commit."
}

# Keep each product's artifact separate so duplicate or substituted filenames cannot
# silently overwrite another build during download.
expected=()
files=()
for product in 'scrib:Scrib' 'max:Scrib-Max' 'remote:Scrib-Remote'; do
    for suffix in '' -arm64-v8a -armeabi-v7a -x86_64 -x86; do
        name="${product#*:}${suffix}.apk"
        path="apk-${product%:*}/$name"
        expected+=("$path")
        files+=("$apks/$path")
    done
done
actual="$(cd "$apks" && find . -mindepth 1 ! -type d -printf '%P\n' | LC_ALL=C sort)"
[[ "$actual" == "$(printf '%s\n' "${expected[@]}" | LC_ALL=C sort)" ]] ||
    die "Expected exactly the 15 APKs from the three product artifacts."

# Compare server digests before publishing; do not accept an incomplete or stale draft.
manifest="$(
    for file in "${files[@]}"; do
        [[ -s "$file" && ! -L "$file" ]] || die "Missing or invalid APK: $file"
        "${ANDROID_HOME:?Android SDK required}/build-tools/35.0.0/apksigner" verify "$file" >&2 || exit 1
        checksum="$(sha256sum < "$file")" || exit 1
        jq -nc --arg key "${file##*/}" --arg value "sha256:${checksum%% *}" '{key: $key, value: $value}' || exit 1
    done | jq -sc 'from_entries'
)"
[[ "$(jq 'length' <<< "$manifest")" == "15" ]] || die "Could not validate the 15 signed APKs."

release_api="repos/$repo/releases"
list_releases() { gh api --paginate --slurp "$release_api?per_page=100"; }
lookup() {
    jq -ce --arg tag "$tag" '[.[][] | select(.tag_name == $tag)] |
        if length > 1 then error("Duplicate releases for " + $tag) else .[0] // {} end'
}
list_assets() { gh api --paginate --slurp "$release_api/$id/assets?per_page=100" | jq -ce 'add // []'; }
verify_tag
releases="$(list_releases)"
release="$(lookup <<< "$releases")"
if [[ "$release" == '{}' ]]; then
    gh release create "$tag" --repo "$repo" --verify-tag --draft \
        --title "Scrib ${tag#v}" --notes-file "$notes"
    releases="$(list_releases)"
    release="$(lookup <<< "$releases")"
fi

id="$(jq -er '.id | select(type == "number")' <<< "$release")"
jq -e '.prerelease == false and (.draft | type == "boolean")' <<< "$release" >/dev/null
assets="$(list_assets)"
if [[ "$(jq -r '.draft' <<< "$release")" == false ]]; then
    # Rebuilds need not be byte-for-byte reproducible. Never mutate a public release.
    jq -e --argjson manifest "$manifest" '
        (map(.name) | sort) == ($manifest | keys) and
        all(.[]; .state == "uploaded" and .size > 0 and (.digest | startswith("sha256:")))
    ' <<< "$assets" >/dev/null || die "$tag is public but incomplete; review it manually."
    printf '%s is already published with all 15 APKs; nothing changed.\n' "$tag"
    exit 0
fi
jq -e --argjson manifest "$manifest" '
    all(.[]; .name as $name | $manifest | has($name))
' <<< "$assets" >/dev/null || die "$tag draft has unexpected attachments; review it manually."

upload=()
for file in "${files[@]}"; do
    if ! jq -e --arg name "${file##*/}" --argjson manifest "$manifest" '
        any(.[]; .name == $name and .state == "uploaded" and .digest == $manifest[$name])
    ' <<< "$assets" >/dev/null; then
        upload+=("$file")
    fi
done
if (( ${#upload[@]} )); then
    gh release upload "$tag" "${upload[@]}" --clobber --repo "$repo"
fi
assets="$(list_assets)"
jq -e --argjson manifest "$manifest" '
    (map(.name) | sort) == ($manifest | keys) and
    all(.[]; .state == "uploaded" and .size > 0 and .digest == $manifest[.name])
' <<< "$assets" >/dev/null || die "$tag draft does not match all 15 tested APK digests."

# Only published stable releases take precedence. An unbuilt newer tag must not
# prevent the newest published release from becoming GitHub's default.
releases="$(list_releases)"
public_tags="$(jq -r '.[][] | select(.draft == false and .prerelease == false) | .tag_name' <<< "$releases")"
legacy_release="$(jq -cr '[.[][] | select(.tag_name == "latest" and .draft == false and .prerelease == false)] | .[0] // {}' <<< "$releases")"
legacy_tag=
legacy_verified=true
if [[ "$legacy_release" != '{}' ]]; then
    # Treat the old moving release as a stable version only when its published
    # title and frozen tag agree with an actual version tag. Never write to it.
    legacy_title="$(jq -r '.name // ""' <<< "$legacy_release")"
    if [[ "$legacy_title" =~ ^Scrib\ ([0-9]+\.[0-9]+(\.[0-9]+)?)$ ]]; then
        legacy_tag="v${BASH_REMATCH[1]}"
        legacy_tip="$(remote_tip latest)" || legacy_verified=false
        [[ -n "$legacy_tip" && "$legacy_tip" == "$(remote_tip "$legacy_tag")" ]] ||
            legacy_verified=false
    else
        legacy_verified=false
    fi
fi
newest="$(
    printf '%s\n' "$tag" "$public_tags" "$legacy_tag" |
        grep -E "$stable_tag" | sort -V | tail -n 1
)"
make_latest=false
if [[ "$legacy_verified" == false ]]; then
    printf '%s\n' 'Legacy default version could not be verified; leaving the default unchanged.' >&2
elif [[ "$tag" == "$newest" ]]; then
    make_latest=true
fi
verify_tag
gh api --method PATCH "$release_api/$id" -F draft=false -f make_latest="$make_latest" |
    jq -er --arg tag "$tag" 'select(.tag_name == $tag and .draft == false) | .html_url'
