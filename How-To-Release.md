```
# Merge develop to main
git checkout main
git merge develop

# Update README (version)
git add README.md
git commit -m "Update README"
./set-release-version.sh

# Push main and wait for deployment
git push origin main
# Wait for GitHub Actions deployment to complete

# Create a tag and push it
VERSION=$(./get-release-version.sh)
git tag ${VERSION}
git push origin ${VERSION}

# Merge main to develop and bump the version
./merge-main-to-develop.sh
./set-next-patch-version.sh
# or
./set-next-minor-version.sh
git push origin develop
```

`rontolisp-maven-plugin/` is outside the root reactor but rides this cycle: it embeds a
rontolisp, so its version says which one. The version scripts above set it together with
the root pom, and the deploy job publishes it right after the core artifact — nothing
extra to run by hand.
