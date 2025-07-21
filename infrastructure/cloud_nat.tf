resource "google_compute_router_nat" "workload_nat" {
  project                            = var.project
  name                               = var.workload_nat_name
  router                             = google_compute_router.workload_network_router.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}