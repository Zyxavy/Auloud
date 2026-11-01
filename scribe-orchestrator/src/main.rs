use clap::Parser;
use tracing_subscriber::EnvFilter;

#[derive(Parser, Debug)]
#[command(name = "scribe-orchestrator", version)]
struct Args {
    #[arg(long)]
    manifest: String,
    #[arg(long, default_value_t = 2)]
    workers: usize,
    #[arg(long, default_value_t = 32)]
    batch_size: usize,
    #[arg(long, default_value = "python")]
    python: String,
    #[arg(long, default_value = "info")]
    log_level: String,
}

fn main() {
    let args = Args::parse();
    let filter = EnvFilter::try_from_str(&args.log_level).unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::fmt().with_env_filter(filter).init();
    tracing::info!("scribe-orchestrator starting, manifest={}", args.manifest);
    // TODO: implement orchestration (Task 5)
    tracing::info!("not yet implemented");
}