resource "google_compute_firewall" "redis_kafka_rule" {
  name    = var.redis_kafka_rule_name
  network = google_compute_network.workloads_network.name

  direction = "INGRESS"

  allow {
    protocol = "tcp"
    ports    = ["6379", "22", "9092", "29092", "2181"]
  }

  source_ranges = var.redis_kafka_rule_ranges

  source_tags = var.redis_kafka_tags

  priority = 100
}


resource "google_compute_firewall" "mongodb_keycloak_ssh_rule" {
  name    = var.mongodb_keycloak_rule_name
  network = google_compute_network.workloads_network.name

  direction = "INGRESS"

  allow {
    protocol = "tcp"
    ports    = ["22", "8080", "27017"]
  }

  source_ranges = var.mongodb_keycloak_rule_ssh_source

  source_tags = var.mongodb_keycloak_tags

  priority = 100
}



resource "google_compute_firewall" "allow_gke_icmp" {
  name    = "gke-allow-icmp"
  network = google_compute_network.workloads_network.name

  direction = "INGRESS"
  priority  = 1000

  source_ranges = [
    "10.1.0.0/16",
  ]

  allow {
    protocol = "icmp"
  }

}
