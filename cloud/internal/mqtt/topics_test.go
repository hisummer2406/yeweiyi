package mqtt

import "testing"

func TestTopicBuilders(t *testing.T) {
	if got, want := serviceTopic("pk", "dev1", IdentifierRequestAlarm), "sys/pk/dev1/thing/service/requestAlarm"; got != want {
		t.Errorf("serviceTopic = %q, want %q", got, want)
	}
	if got, want := propertyTopic("pk", "dev1", IdentifierRequestAlarm), "sys/pk/dev1/thing/event/property/post/requestAlarm"; got != want {
		t.Errorf("propertyTopic = %q, want %q", got, want)
	}
	if got, want := eventTopic("pk", "dev1", IdentifierIsOnline), "sys/pk/dev1/thing/event/isOline/post"; got != want {
		t.Errorf("eventTopic = %q, want %q", got, want)
	}
}

func TestParsePropertyTopic(t *testing.T) {
	device, identifier, ok := parsePropertyTopic("sys/pk/dev1/thing/event/property/post/requestAlarm")
	if !ok || device != "dev1" || identifier != "requestAlarm" {
		t.Fatalf("got (%q, %q, %v)", device, identifier, ok)
	}

	if _, _, ok := parsePropertyTopic("sys/pk/dev1/thing/event/isOline/post"); ok {
		t.Fatalf("expected event-shaped topic to be rejected by parsePropertyTopic")
	}
}

func TestParseEventTopic(t *testing.T) {
	device, ok := parseEventTopic("sys/pk/dev1/thing/event/isOline/post")
	if !ok || device != "dev1" {
		t.Fatalf("got (%q, %v)", device, ok)
	}

	if _, ok := parseEventTopic("sys/pk/dev1/thing/event/property/post/requestAlarm"); ok {
		t.Fatalf("expected property-shaped topic to be rejected by parseEventTopic")
	}
}

func TestTopicFilters(t *testing.T) {
	if got, want := propertyTopicFilter("pk"), "sys/pk/+/thing/event/property/post/+"; got != want {
		t.Errorf("propertyTopicFilter = %q, want %q", got, want)
	}
	if got, want := eventTopicFilter("pk", IdentifierMsg), "sys/pk/+/thing/event/msg/post"; got != want {
		t.Errorf("eventTopicFilter = %q, want %q", got, want)
	}
}
