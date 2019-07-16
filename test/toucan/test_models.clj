(ns toucan.test-models
  (:require (toucan.test-models address category phone-number user venue)))

(potemkin/import-vars
 [toucan.test-models.address Address]
 [toucan.test-models.category Category]
 [toucan.test-models.phone-number PhoneNumber]
 [toucan.test-models.user User]
 [toucan.test-models.venue Venue])
