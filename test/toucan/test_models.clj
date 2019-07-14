(ns toucan.test-models
  (:require (toucan.test-models address category user venue)))

(potemkin/import-vars
 [toucan.test-models.address Address]
 [toucan.test-models.category Category]
 [toucan.test-models.user User]
 [toucan.test-models.venue Venue])
